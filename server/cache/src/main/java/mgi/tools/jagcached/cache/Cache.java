package mgi.tools.jagcached.cache;

import mgi.tools.jagcached.ArchiveType;
import mgi.tools.jagcached.Helper;
import mgi.utilities.ByteBuffer;
import mgi.utilities.Whirlpool;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.channels.FileChannel;


public class Cache {

	private static final String DATA_FILE = "main_file_cache.dat2";
	private static final String INDEX_FILE = "main_file_cache.idx";

	/**
	 * Largest group the master index (idx255) may hold. Build 241's reference table for the models
	 * archive is 525,411 bytes, more than the old 500,000 byte limit, so the limit is now 16 MiB.
	 */
	public static final int MASTER_INDEX_MAX_GROUP_SIZE = 0x1000000;
	/**
	 * Largest group a regular index may hold (was 1,000,000 bytes).
	 */
	public static final int INDEX_MAX_GROUP_SIZE = 0x1000000;

	private String path;

	private Index[] indexes;
	private Index masterindex;
	private Archive[] archives;
	private int[] crcs;

	public final boolean readOnly;

	private Cache(String path, boolean readOnly) {
		this.readOnly = readOnly;
		this.path = path;
		openFiles(path);
	}

	/**
	 * The directory this cache was opened from.
	 */
	public String getPath() {
		return path;
	}

	private void openFiles(String path) {
		try {
			String seperator = System.getProperty("file.separator","/");

			@SuppressWarnings("resource") RandomAccessFile dataFile = new RandomAccessFile(path + seperator + DATA_FILE, readOnly ? "r" : "rw");
			@SuppressWarnings("resource") RandomAccessFile referenceFile = new RandomAccessFile(path + seperator + INDEX_FILE + "255", readOnly ? "r" : "rw");

			FileChannel dataFileChannel = dataFile.getChannel();
			FileChannel referenceFileChannel = referenceFile.getChannel();

			masterindex = new Index(255, dataFileChannel, referenceFileChannel, MASTER_INDEX_MAX_GROUP_SIZE);

			int numIndices = masterindex.groupCount();
			indexes = new Index[numIndices];
			archives = new Archive[numIndices];
			for (int i = 0; i < numIndices; i++) {
				final File idx = new File(path + seperator + INDEX_FILE + i);
				if (readOnly && !idx.exists()) {
					// Build 241 has no idx16 (old world map data) and no idx23; the master index still counts them.
					indexes[i] = Index.missing(i);
				} else {
					@SuppressWarnings("resource") RandomAccessFile indexFile = new RandomAccessFile(idx, readOnly ? "r" : "rw");
					indexes[i] = new Index(i, dataFileChannel, indexFile.getChannel(), INDEX_MAX_GROUP_SIZE);
				}
				archives[i] = new Archive(i, this);
			}

			crcs = new int[numIndices];
			for (int i = 0; i < archives.length; i++) {
				crcs[i] = getArchive(i).getCRC32();
			}
		} catch (IOException ioex) {
			ioex.printStackTrace();
		}
	}

	/**
	 * Finishing all pending operations.
	 */
	public void flush() {
		if (archives == null)
			throw new RuntimeException("Cache is closed.");
		for (Archive archive : archives)
			if (archive.isLoaded())
				archive.finish();
	}

	/**
	 * Finishing all pending operations then
	 * Closes cache and disposes all data.
	 * Calling close() on closed cache has no effect.
	 */
	public void close() {
		if (indexes == null)
			return; // closed cache
		flush();
		for (Index index : indexes)
			index.close();
		masterindex.close();

		archives = null;
		indexes = null;
		masterindex = null;
	}

	/**
	 * Opens cache at given path.
	 * @param path
	 * Folder where cache is located.
	 * @return
	 * Opened cache or null if something failed.
	 */
	public static Cache openCache(String path) {
		return openCache(path, false);
	}

	public static Cache openCache(String path, boolean readOnly) {
		return new Cache(path, readOnly);
	}

	/**
	 * Create's new cache on given path.
	 * @param path
	 * Folder where to create new cache.
	 * @param indicesCount
	 * Count of indices , can't be lower than 0 or higher than 254.
	 * @return
	 * Created cache or null if something failed.
	 */
	public static Cache createNewCache(String path, int indicesCount) {
		if (indicesCount < 0 || indicesCount > 254)
			return null;

		File file = new File(path);
		if (file.isFile())
			return null;

		if (!file.exists())
			file.mkdirs();

		if (!file.isDirectory())
			return null;

		try {
			String seperator = System.getProperty("file.separator","/");
			File data = new File(path + seperator + DATA_FILE);
			if (data.exists() || !data.createNewFile() || !data.canWrite() || !data.canRead())
				return null;

			for (int i = 0; i < indicesCount; i++) {
				File index = new File(path + seperator + INDEX_FILE + i);
				if (index.exists() || !index.createNewFile() || !index.canWrite() || !data.canRead())
					return null;
			}

			File index255 = new File(path + seperator + INDEX_FILE + "255");
			if (index255.exists() || !index255.createNewFile() || !index255.canWrite() || !index255.canRead())
				return null;

			RandomAccessFile dataFile = new RandomAccessFile(path + seperator + DATA_FILE, "rw");
			RandomAccessFile referenceFile = new RandomAccessFile(path + seperator + INDEX_FILE + "255", "rw");
			Index store_255 = new Index(255, dataFile.getChannel(), referenceFile.getChannel(), 0x7a120);

			for (int i = 0; i < indicesCount; i++) {
				byte[] pdata = Helper.encodeFITContainer(new byte[] { 5, 0, 0, 0 } , 0); // empty fs (protocol 5, props 0, folders count - 0)
				store_255.put(i, new ByteBuffer(pdata), pdata.length);
			}

			dataFile.getChannel().close();
			referenceFile.getChannel().close();


			return new Cache(path, false);
		}
		catch (IOException ex) {
			ex.printStackTrace();
			return null;
		}
	}

	/**
	 * Whether this cache contains the given archive (it has a reference table in the master index).
	 * Build 241 has no archive 16 or 23; build 211 has no archive 22 or 24.
	 */
	public boolean hasArchive(int id) {
		final Archive archive = getArchive(id);
		return archive != null && archive.isLoaded();
	}

	public boolean hasArchive(final ArchiveType archive) {
		return hasArchive(archive.getId());
	}

	public Index getIndex(final ArchiveType archive) {
		return getIndex(archive.getId());
	}

	/**
	 * Get's specific cache index.
	 * Returns null if index is not available in this cache.
	 * Note:Using this method to get master index (idx255) results in
	 * null return, use getMasterIndex() instead.
	 */
	public Index getIndex(int id) {
		if (id == 255) {
			return getMasterIndex();
		}
		if (indexes == null)
			throw new RuntimeException("Cache is closed.");
		if (id < 0 || id > indexes.length - 1)
			return null;
		return indexes[id];
	}

	/**
	 * Gets specific cache archive.
	 * If it's not loaded then it loads it.
	 */
	public Archive getArchive(int id) {
		if (archives == null)
			throw new RuntimeException("Cache is closed.");
		if (id < 0 || id > archives.length - 1)
			return null;
		Archive archive = archives[id];
		if (!archive.isLoaded())
			archive.load();
		return archive;
	}

	public void setArchive(final ArchiveType archiveType, Archive archive) {
		archives[archiveType.getId()] = archive;
		archive.setChanged(true);
	}

	/**
	 * Gets specific cache archive.
	 * If it's not loaded then it loads it.
	 */
	public Archive getArchive(final ArchiveType archive) {
		return getArchive(archive.getId());
	}

	public Archive[] getArchives() {
		return archives;
	}

	/**
	 * Gets information about files store.
	 */
	public Index getMasterIndex() {
		if (masterindex == null)
			throw new RuntimeException("Cache is closed.");
		return masterindex;
	}

	/**
	 * Gets count of indices in this cache.
	 */
	public int getArchiveCount() {
		if (indexes == null)
			throw new RuntimeException("Cache is closed.");
		return indexes.length;
	}

	/**
	 * Gets an array of all index CRCs.
	 */
	public int[] getCrcs() {
		if (indexes == null)
			throw new RuntimeException("Cache is closed.");
		return crcs;
	}

	/**
	 * Generates information store descriptor (AKA update keys, 255_255 &amp; such )
	 * Generated file does not contain update server header.
	 * flush() is called to ensure correct data.
	 */
	public ByteBuffer generateInformationStoreDescriptor(BigInteger exponent,BigInteger modulus) {
		if (archives == null)
			throw new RuntimeException("Cache is closed.");
		for (Archive archive : archives)
			if (!archive.isLoaded())
				archive.load();
		flush();
		int indicesCount = getArchiveCount();
		ByteBuffer alloc = new ByteBuffer((1 + (indicesCount * 72) + 1 + 64) * 10);
		alloc.writeByte(indicesCount);
		for (int i = 0; i < indicesCount; i++) {
			alloc.writeInt(archives[i].getCRC32());
			alloc.writeInt(archives[i].getVersion());
			// a missing archive (e.g. 16 or 23 in build 241) has crc 0, version 0 and an empty digest
			alloc.writeBytes(archives[i].isLoaded() ? archives[i].getDigest() : new byte[64], 0, 64);
		}
		byte[] selfDigest = Whirlpool.whirlpool(alloc.getBuffer(), 0, alloc.getPosition());
		ByteBuffer rsa = new ByteBuffer(65);
		rsa.writeByte(10);
		rsa.writeBytes(selfDigest, 0, 64);
		BigInteger data = new BigInteger(rsa.getBuffer());
		byte[] encrypted = data.modPow(exponent, modulus).toByteArray();
		alloc.writeBytes(encrypted, 0, encrypted.length);
		return new ByteBuffer(alloc.toArray(0, alloc.getPosition()));
	}

	/**
	 * Generates information store descriptor (AKA update keys, 255_255 &amp; such )
	 * Generated file does not contain update server header.
	 * flush() is called to ensure correct data.
	 */
	public ByteBuffer generateInformationStoreDescriptor() {
		if (archives == null) {
			throw new RuntimeException("Cache is closed.");
		}
		for (Archive archive : archives)
			if (!archive.isLoaded())
				archive.load();
		flush();
		int indicesCount = getArchiveCount();
		ByteBuffer alloc = new ByteBuffer((1 + (indicesCount * 72) + 1 + 64) * 10);

		for (int i = 0; i < indicesCount; i++) {
			alloc.writeInt(archives[i].getCRC32());
			alloc.writeInt(archives[i].getVersion());
		}
		return Helper.encodeFITContainer(new ByteBuffer(alloc.toArray(0, alloc.getPosition())), 0, Helper.COMPRESSION_NONE);
	}

}
