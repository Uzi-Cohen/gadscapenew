package com.zenyte.game.model.polls;

import com.gadscape.game.rev241.Gad211;
import com.zenyte.cores.CoresManager;
import com.zenyte.cores.ScheduledExternalizable;
import com.zenyte.game.model.polls.AnsweredPoll.AnsweredPollQuestion;
import com.zenyte.game.model.ui.InterfacePosition;
import com.zenyte.game.packet.PacketDispatcher;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import mgi.utilities.StringFormatUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author Kris | 26. march 2018 : 3:15.05
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class PollManager implements ScheduledExternalizable {
	private static final Logger log = LoggerFactory.getLogger(PollManager.class);
	public static final Int2ObjectOpenHashMap<Poll> map = new Int2ObjectOpenHashMap<>();
	/* GadScape's 211 poll screens; on the 241 cache gadconv moved them (241 reuses the ids), idmap.json "interface". */
	public static final int INTERFACE_ID_211 = 345;
	public static final int HISTORY_INTERFACE_ID_211 = 310;
	public static final int VOTING_INTERFACE_ID_211 = 400;
	public static final int INTERFACE_ID = Gad211.iface(INTERFACE_ID_211);
	private static final int SHIFTED_INTERFACE_ID = INTERFACE_ID << 16;
	public static final int HISTORY_INTERFACE_ID = Gad211.iface(HISTORY_INTERFACE_ID_211);
	public static final int VOTING_INTERFACE_ID = Gad211.iface(VOTING_INTERFACE_ID_211);
	private static final int SHIFTED_VOTING_INTERFACE_ID = VOTING_INTERFACE_ID << 16;
	/*
	 * The 211 poll scripts the server runs, resolved like the screens (idmap.json "script": gadconv ports a 211
	 * vanilla script 241 replaced, e.g. 603 -> 47039). The values are their 211 headers (int, string arguments).
	 */
	private static final int SCRIPT_TITLE = Gad211.script(603);
	private static final int SCRIPT_BUTTON = Gad211.script(604);
	private static final int SCRIPT_TEXT = Gad211.script(609);
	private static final int SCRIPT_LINK = Gad211.script(610);
	private static final int SCRIPT_SCROLL = Gad211.script(618);
	private static final int SCRIPT_QUESTION = Gad211.script(619);
	private static final int SCRIPT_ANSWER = Gad211.script(620);
	private static final int SCRIPT_RESULTS = Gad211.script(624);
	private static final int SCRIPT_HISTORY = Gad211.script(627);
	private static final int[][] SCRIPT_HEADERS_211 = {
			{SCRIPT_TITLE, 5, 2}, {SCRIPT_BUTTON, 2, 1}, {SCRIPT_TEXT, 7, 1}, {SCRIPT_LINK, 1, 2}, {SCRIPT_SCROLL, 4, 0},
			{SCRIPT_QUESTION, 2, 1}, {SCRIPT_ANSWER, 2, 0}, {SCRIPT_RESULTS, 36, 1}, {SCRIPT_HISTORY, 1, 2}};
	private static final int DESCRIPTION_HEX_COLOUR = 16750623;
	private static final int VOTES_HEX_COLOUR = 16777215;
	private static final int VOTE_COMPONENT_OFFSET = 12;

	/**
	 * GadScape's poll screens are 211 interfaces 345 (poll, 13 components), 310 (history, 6) and 400 (voting, 14).
	 * Build 241 reuses those ids (sailing_intro_hud, gryphon_boss, trr_display) and rewrote the vanilla poll scripts
	 * 603-627. gadconv moves the screens (cacheV: 5202, 5201, 5203) and ports the 211 scripts whose name changed
	 * (603-617, 622); the polls open only when the screens are GadScape's and every script the server runs has its
	 * 211 header in the cache. CACHE NEED (cacheV): 618-620, 624 and 627 kept their names in 241 but not their
	 * arguments, so gadconv keeps 2746's; until it ports them the polls stay "not available" instead of half-working.
	 */
	private static boolean pollInterfacesPresent() {
		if (com.zenyte.game.GameInterface.isReusedBy241(INTERFACE_ID)
				|| com.zenyte.game.GameInterface.isReusedBy241(HISTORY_INTERFACE_ID)
				|| com.zenyte.game.GameInterface.isReusedBy241(VOTING_INTERFACE_ID)) {
			return false;
		}
		for (final int[] header : SCRIPT_HEADERS_211) {
			final mgi.types.clientscript.ClientScriptDefinitions script;
			try {
				script = mgi.types.clientscript.ClientScriptDefinitions.get(header[0]);
			} catch (final RuntimeException e) {
				return false;
			}
			if (script == null || script.getIntArgumentCount() != header[1] || script.getStringArgumentCount() != header[2]) {
				return false;
			}
		}
		return true;
	}

	public PollManager() {
		player = null;
	}

	public PollManager(final Player player) {
		this.player = player;
	}

	private final Player player;
	private Poll poll;
	private int[] votes;
	private final Map<Integer, AnsweredPoll> polls = new HashMap<>();

	/**
	 * Loads all the polls that the player has answered in the past. Executed on a thread pool to avoid locking the main thread up.
	 */
	public void loadAnsweredPolls() {
		CoresManager.getServiceProvider().executeNow(() -> {
			try {
				final Path path = path(player);
				if (!Files.exists(path)) {
					return;
				}
				final AnsweredPoll[] polls;
				try (final BufferedReader reader = Files.newBufferedReader(path)) {
					polls = getGSON().fromJson(reader, AnsweredPoll[].class);
				}
				for (int i = polls.length - 1; i >= 0; i--) {
					final AnsweredPoll poll = polls[i];
					if (poll == null) {
						continue;
					}
					this.polls.put(poll.getPollId(), poll);
				}
			} catch (final Exception e) {
				log.error("", e);
			}
		});
	}

	/**
	 * Saves all the polls this player has answered. Executed on a thread pool to avoid locking the main thread up.
	 */
	public void saveAnsweredPolls() {
		CoresManager.getServiceProvider().executeNow(() -> {
			try {
				final Collection<AnsweredPoll> polls = this.polls.values();
				final String toJson = getGSON().toJson(polls);
				Files.writeString(path(player), toJson);
			} catch (final Exception e) {
				log.error("", e);
			}
		});
	}

	private static Path path(Player player) {
		return Path.of("data", "polls", "answers",
				player.getPlayerInformation().getUsername() + ".json");
	}

	/**
	 * Opens up the requested poll. Initially it'll open the loading screen, a tick afterwards(or more, if more time is needed), sends the
	 * information about the poll.
	 * 
	 * @param poll
	 *            the poll to open up.
	 */
	public final void open(final Poll poll) {
		if (!pollInterfacesPresent()) {
			player.sendMessage("Polls are not available right now.");
			return;
		}
		this.poll = poll;
		sendLoadingScreen();
		CoresManager.getServiceProvider().executeWithDelay(() -> sendPollStatistics(), 1);
	}

	/**
	 * Sends the loading screen for polls while it loads the information up.
	 */
	private final void sendLoadingScreen() {
		player.getInterfaceHandler().sendInterface(InterfacePosition.CENTRAL, INTERFACE_ID);
		final PacketDispatcher dispatcher = player.getPacketDispatcher();
		player.getPacketDispatcher().sendClientScript(SCRIPT_TITLE, poll.getTitle(), SHIFTED_INTERFACE_ID | 2, SHIFTED_INTERFACE_ID | 12, SHIFTED_INTERFACE_ID | 11, SHIFTED_INTERFACE_ID | 10, "Loading...");
		dispatcher.sendClientScript(SCRIPT_BUTTON, SHIFTED_INTERFACE_ID | 8, SHIFTED_INTERFACE_ID | 9);
		dispatcher.sendClientScript(SCRIPT_BUTTON, SHIFTED_INTERFACE_ID | 6, SHIFTED_INTERFACE_ID | 7);
		dispatcher.sendClientScript(SCRIPT_BUTTON, SHIFTED_INTERFACE_ID | 4, SHIFTED_INTERFACE_ID | 5);
		dispatcher.sendComponentText(INTERFACE_ID, 3, "");
		player.getVarManager().sendVar(375, 8);
	}

	/**
	 * Sends the actual information about the poll, including.. Whether the player has voted on it or not, if they have, their chosen option
	 * is shown. The title, description, hyperlinks etc are shown. Informs the player if the poll has already closed, if so, the options to
	 * vote or refresh disappear. Informs the player if they can amend their vote, if so, the originally 'vote' button turns into 'amend'
	 * button.
	 */
	private final void sendPollStatistics() {
		player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
		player.getInterfaceHandler().sendInterface(InterfacePosition.CENTRAL, INTERFACE_ID);
		player.getPacketDispatcher().sendClientScript(SCRIPT_TITLE, poll.getTitle(), SHIFTED_INTERFACE_ID | 2, SHIFTED_INTERFACE_ID | 12, SHIFTED_INTERFACE_ID | 11, SHIFTED_INTERFACE_ID | 10, "Building...");
		final PacketDispatcher dispatcher = player.getPacketDispatcher();
		dispatcher.sendComponentVisibility(INTERFACE_ID, SHIFTED_INTERFACE_ID | 10, true);
		dispatcher.sendClientScript(SCRIPT_TEXT, poll.getDescription() + "|" + poll.getFormattedEndDate(), DESCRIPTION_HEX_COLOUR, 0, 495, 495, 12, 5, SHIFTED_INTERFACE_ID | 11);
		final String hyperlink = poll.getHyperlink();
		if (hyperlink != null) {
			dispatcher.sendClientScript(SCRIPT_LINK, hyperlink, SHIFTED_INTERFACE_ID | 11);
		}
		dispatcher.sendClientScript(SCRIPT_TEXT, "Votes: " + StringFormatUtil.format(poll.getVotes()), VOTES_HEX_COLOUR, 1, 496, 496, 12, 5, SHIFTED_INTERFACE_ID | 11);
		final AnsweredPoll answeredPoll = polls.get(poll.getPollId());
		final boolean hasVoted = answeredPoll != null;
		final PollQuestion[] questions = poll.getQuestions();
		final int length = questions.length;
		final List<Object> list = new ArrayList<>();
		for (int i = 0; i < length; i++) {
			final PollQuestion question = questions[i];
			final String questionHyperlink = question.getHyperlink();
			final String message = "Question " + (i + 1) + "|" + question.getQuestion() + "|" + (questionHyperlink == null ? "||" : (questionHyperlink + "|")) + question.buildAnswers();
			list.clear();
			list.add(i);
			list.add(-1);
			list.add(message);
			final PollAnswer[] pollAnswers = question.getAnswers();
			list.add(pollAnswers.length);
			list.add(question.getAnswer(answeredPoll, i));
			for (int x = 0; x < pollAnswers.length; x++) {
				list.add(pollAnswers[x].getVotes());
			}
			dispatcher.sendClientScript(SCRIPT_RESULTS, list.toArray(new Object[0]));
		}
		dispatcher.sendClientScript(SCRIPT_TEXT, DESCRIPTION_HEX_COLOUR, 1, 496, 496, 12, 5, SHIFTED_INTERFACE_ID | 11);
		dispatcher.sendClientScript(SCRIPT_SCROLL, SHIFTED_INTERFACE_ID | 11, SHIFTED_INTERFACE_ID | 12, SHIFTED_INTERFACE_ID | 9, 1);
		dispatcher.sendClientScript(SCRIPT_BUTTON, SHIFTED_INTERFACE_ID | 8, SHIFTED_INTERFACE_ID | 9, "History");
		final boolean closed = poll.isClosed();
		dispatcher.sendClientScript(SCRIPT_BUTTON, SHIFTED_INTERFACE_ID | 6, SHIFTED_INTERFACE_ID | 7, closed || hasVoted && !poll.isAmendable() ? "" : "Refresh");
		dispatcher.sendClientScript(SCRIPT_BUTTON, SHIFTED_INTERFACE_ID | 4, SHIFTED_INTERFACE_ID | 5, closed || hasVoted && !poll.isAmendable() ? "" : (hasVoted ? "Amend" : "Vote"));
		if (!closed) {
			if (hasVoted) {
				if (poll.isAmendable()) {
					dispatcher.sendComponentText(INTERFACE_ID, 3, "Votes in this poll can be amended.");
				} else {
					dispatcher.sendComponentText(INTERFACE_ID, 3, "Votes in this poll cannot be amended.");
				}
			} else {
				dispatcher.sendComponentText(INTERFACE_ID, 3, "You have not yet voted in this poll.");
			}
		} else {
			dispatcher.sendComponentText(INTERFACE_ID, 3, "This poll has closed.");
		}
		dispatcher.sendClientScript(135, SHIFTED_INTERFACE_ID | 3, 495);
	}

	/**
	 * Opens and displays the history of all the polls, in a declining order by date, with the recent polls at the very top. The end and
	 * close dates of the polls are shown.
	 */
	public final void sendHistory() {
		if (!pollInterfacesPresent()) {
			player.sendMessage("Polls are not available right now.");
			return;
		}
		player.getInterfaceHandler().sendInterface(InterfacePosition.CENTRAL, HISTORY_INTERFACE_ID);
		final StringBuilder titleBuilder = new StringBuilder();
		final StringBuilder dateBuilder = new StringBuilder();
		final int size = map.size();
		for (int i = size; i >= 1; i--) {
			final Poll poll = map.get(i);
			if (poll == null) {
				continue;
			}
			titleBuilder.append("<col=df780f>").append(poll.getTitle()).append("</col>|");
			dateBuilder.append(poll.getFormattedPollDates()).append("|");
		}
		player.getPacketDispatcher().sendClientScript(SCRIPT_HISTORY, map.size(), titleBuilder.toString(), dateBuilder.toString());
	}

	/**
	 * Either sends the voting interface, selects an option, informs the player about an unanswered question or clears all the selected
	 * options.
	 * 
	 * @param clear
	 *            whether to clear the selected options.
	 * @param questionId
	 *            if trying to submit without having a question answered, informs them and asks to answer the question.
	 */
	public final void vote(final boolean clear, final int questionId) {
		if (clear || votes == null) {
			votes = new int[poll.getQuestions().length];
		}
		player.getInterfaceHandler().sendInterface(InterfacePosition.CENTRAL, VOTING_INTERFACE_ID);
		final PacketDispatcher dispatcher = player.getPacketDispatcher();
		dispatcher.sendComponentSettings(VOTING_INTERFACE_ID, 1, 0, 3199, AccessMask.CLICK_OP1);
		player.getPacketDispatcher().sendClientScript(SCRIPT_TITLE, poll.getTitle(), SHIFTED_VOTING_INTERFACE_ID | 3, SHIFTED_VOTING_INTERFACE_ID | 13, SHIFTED_VOTING_INTERFACE_ID | 10, SHIFTED_VOTING_INTERFACE_ID | 9, "Building...");
		player.getPacketDispatcher().sendClientScript(SCRIPT_TEXT, poll.getDescription() + "|" + poll.getFormattedEndDate(), DESCRIPTION_HEX_COLOUR, 0, 495, 495, 12, 5, SHIFTED_VOTING_INTERFACE_ID | 10);
		final String hyperlink = poll.getHyperlink();
		if (hyperlink != null) {
			player.getPacketDispatcher().sendClientScript(SCRIPT_LINK, hyperlink, SHIFTED_INTERFACE_ID | 10);
		}
		final PollQuestion[] questions = poll.getQuestions();
		final int length = questions.length;
		if (clear) {
			for (int i = 0; i < length; i++) {
				final PollQuestion question = questions[i];
				final String questionHyperlink = question.getHyperlink();
				final String message = "Question " + (i + 1) + "|" + question.getQuestion() + "|" + (questionHyperlink == null ? "||" : (questionHyperlink + "|")) + question.getFormattedPollAnswers();
				/** TODO: +2 = CANCEL */
				final int len = question.getAnswers().length;
				final int offset = len == 2 ? -4 : len > 3 ? ((len - 3) << 2) : 0;
				dispatcher.sendClientScript(SCRIPT_QUESTION, VOTE_COMPONENT_OFFSET + (i * 256) + offset, message, 0);
			}
		} else {
			for (int i = 0; i < length; i++) {
				final PollQuestion question = questions[i];
				final int len = question.getAnswers().length;
				final int offset = len == 2 ? -4 : len > 3 ? ((len - 3) << 2) : 0;
				dispatcher.sendClientScript(SCRIPT_ANSWER, VOTE_COMPONENT_OFFSET + (i * 256) + offset, votes[i]);
			}
		}
		dispatcher.sendClientScript(SCRIPT_SCROLL, SHIFTED_VOTING_INTERFACE_ID | 10, SHIFTED_VOTING_INTERFACE_ID | 13, SHIFTED_VOTING_INTERFACE_ID | 9, questionId == -1 ? 0 : 1);
		if (questionId == -1) {
			dispatcher.sendComponentText(VOTING_INTERFACE_ID, 4, "Cast your vote.");
		} else {
			dispatcher.sendComponentText(VOTING_INTERFACE_ID, 4, "<col=ff0000>Please answer question " + questionId + ".</col>");
		}
		dispatcher.sendClientScript(SCRIPT_BUTTON, SHIFTED_VOTING_INTERFACE_ID | 5, SHIFTED_VOTING_INTERFACE_ID | 6, "Clear");
		dispatcher.sendClientScript(SCRIPT_BUTTON, SHIFTED_VOTING_INTERFACE_ID | 7, SHIFTED_VOTING_INTERFACE_ID | 8, "Cancel");
		dispatcher.sendClientScript(SCRIPT_BUTTON, SHIFTED_VOTING_INTERFACE_ID | 11, SHIFTED_VOTING_INTERFACE_ID | 12, "Vote");
	}

	/**
	 * Handles the interface clicks.
	 * 
	 * @param interfaceId
	 *            the id of the dialogue interface being managed.
	 * @param componentId
	 *            the component being clicked.
	 * @param slotId
	 *            the slot in that component being clicked.
	 */
	public final void handleInterface(final int interfaceId, final int componentId, final int slotId) {
		if (interfaceId == INTERFACE_ID) {
			switch (componentId) {
			case 5: 
				vote(true, -1);
				return;
			case 7: 
				open(poll);
				return;
			case 9: 
				sendHistory();
				return;
			}
			return;
		}
		if (interfaceId == HISTORY_INTERFACE_ID) {
			if (componentId != 4) {
				return;
			}
			final Poll poll = map.get(map.size() - slotId);
			if (poll == null) {
				return;
			}
			open(poll);
			return;
		}
		if (interfaceId == VOTING_INTERFACE_ID) {
			switch (componentId) {
			case 2: 
				final int questionId = slotId >> 5;
				final int answerId = slotId % 32;
				if (questionId > votes.length) {
					return;
				}
				votes[questionId] = 1 << answerId;
				vote(false, -1);
				return;
			case 6: 
				vote(true, -1);
				return;
			case 8: 
				open(this.poll);
				return;
			case 12: 
				for (int i = 0; i < votes.length; i++) {
					if (votes[i] == 0) {
						vote(false, i + 1);
						return;
					}
				}
				submitVotes();
				return;
			}
		}
	}

	/**
	 * Submits the players votes and saves them. Sends a dialogue that informs the player so.
	 */
	private void submitVotes() {
		player.getDialogueManager().start(new Dialogue(player) {
			@Override
			public void buildDialogue() {
				player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
				plain("Submitting your vote...", false);
				CoresManager.getServiceProvider().executeWithDelay(() -> {
					final boolean increaseNumbers = !polls.containsKey(poll.getPollId());
					if (increaseNumbers) {
						poll.setVotes(poll.getVotes() + 1);
					}
					final PollQuestion[] questions = poll.getQuestions();
					final int length = questions.length;
					for (int i = 0; i < length; i++) {
						final PollQuestion question = questions[i];
						if (i >= votes.length) {
							continue;
						}
						final int votedOption = votes[i] >> 1;
						final PollAnswer[] answers = question.getAnswers();
						if (votedOption >= answers.length) {
							continue;
						}
						final PollAnswer answer = answers[votedOption];
						if (increaseNumbers) {
							answer.setVotes(answer.getVotes() + 1);
						}
					}
					final AnsweredPoll answeredPoll = new AnsweredPoll();
					answeredPoll.setPollId(poll.getPollId());
					answeredPoll.setSubmitDate(LocalDateTime.now());
					answeredPoll.setTitle(poll.getTitle());
					final AnsweredPollQuestion[] answeredQuestions = new AnsweredPollQuestion[length];
					for (int i = 0; i < length; i++) {
						final AnsweredPollQuestion q = new AnsweredPollQuestion();
						if (i >= questions.length || i >= votes.length) {
							continue;
						}
						q.setQuestion(questions[i].getQuestion());
						q.setAnswer(questions[i].getAnswers()[votes[i] >> 1].getChoice());
						answeredQuestions[i] = q;
					}
					answeredPoll.setQuestions(answeredQuestions);
					polls.put(poll.getPollId(), answeredPoll);
					saveAnsweredPolls();
					player.getDialogueManager().next();
				}, 1);
				plain("Thank you for voting.").executeAction(() -> open(poll));
			}
		});
	}

	@Override
	public Logger getLog() {
		return log;
	}

	@Override
	public int writeInterval() {
		return 5;
	}

	@Override
	public void read(final @NotNull BufferedReader reader) {
		final Poll[] polls = getGSON().fromJson(reader, Poll[].class);
		final ArrayList<Poll> pollsList = new ArrayList<>(polls.length);
		pollsList.addAll(Arrays.asList(polls));
		pollsList.sort((p1, p2) -> p1 == null || p2 == null ? 0 : Math.max(p1.getPollId(), p2.getPollId()));
		for (final Poll p : pollsList) {
			if (p == null) {
				continue;
			}
			map.put(p.getPollId(), p);
		}
	}

	@Override
	public void write() {
		out(getGSON().toJson(new ArrayList<>(map.values())));
	}

	@Override
	public String path() {
		return "data/polls/polls.json";
	}

}
