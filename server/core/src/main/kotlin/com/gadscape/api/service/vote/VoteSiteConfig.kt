package com.gadscape.api.service.vote

import com.gadscape.api.model.VoteSite
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where each toplist's vote page is and whether it takes votes (the vote screen greys a closed site out).
 *
 * The defaults are below: RuneLocus and RSPS-List are open (the pages the old vote system used), the four newer
 * sites are closed until their pages are set. `data/vote-sites.json` (optional, read at start-up) changes any of
 * them, by VoteSite name:
 *
 *     {"TOPG": {"enabled": true, "url": "https://topg.org/runescape-private-servers/in-12345-{callback}"}}
 *
 * `{callback}` in a url becomes the voter: the API user id while the old vote API is on, else the character name.
 * With the website link on, Vote asks the website for its signed vote link instead and uses these only when that fails.
 */
object VoteSiteConfig {

    private val logger = LoggerFactory.getLogger(VoteSiteConfig::class.java)
    private val file: Path = Path.of("data", "vote-sites.json")

    data class Entry(val url: String, val enabled: Boolean)

    private val DEFAULTS = mapOf(
        VoteSite.RUNELOCUS to Entry("https://www.runelocus.com/top-rsps-list/GadScape-is-back/vote?callback={callback}", true),
        VoteSite.RSPS_LIST to Entry("https://www.rsps-list.com/index.php?a=in&u=nrpk&id={callback}", true),
        VoteSite.TOPG to Entry("https://topg.org/runescape-private-servers/", false),
        VoteSite.TOP100ARENA to Entry("https://www.top100arena.com/category/runescape-private-servers", false),
        VoteSite.RUNELIST to Entry("https://runelist.io/", false),
        VoteSite.MOPARSCAPE to Entry("https://www.moparscape.org/rsps-list/", false),
    )

    private val entries: Map<VoteSite, Entry> by lazy { load() }

    fun entry(site: VoteSite): Entry = entries[site] ?: DEFAULTS.getValue(site)

    fun isEnabled(site: VoteSite) = entry(site).enabled

    /** [site]'s vote page for [callback] (see the class comment). */
    fun url(site: VoteSite, callback: String) = entry(site).url.replace("{callback}", callback)

    private fun load(): Map<VoteSite, Entry> {
        val out = HashMap(DEFAULTS)
        if (!Files.exists(file)) return out
        try {
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            for ((name, value) in root.entrySet()) {
                val site = VoteSite.find(name)
                if (site == null || !value.isJsonObject) {
                    logger.warn("{}: no vote site called {}", file, name)
                    continue
                }
                val o = value.asJsonObject
                val base = out.getValue(site)
                out[site] = Entry(
                    url = o.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: base.url,
                    enabled = o.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: base.enabled,
                )
            }
        } catch (e: Exception) {
            logger.error("Couldn't read {}, using the default vote sites", file, e)
        }
        return out
    }
}
