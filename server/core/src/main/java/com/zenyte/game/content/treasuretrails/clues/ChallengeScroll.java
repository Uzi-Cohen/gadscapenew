package com.zenyte.game.content.treasuretrails.clues;

/**
 * Contains all the available challenge scrolls from OSRS. Challenge scrolls are
 * effectively just random questions proposed by a random NPC in OSRS; the
 * player has to answer the question correctly to be able to receive a casket.
 * The scrolls' answers are always numeric.
 * 
 * @author Kris | 29. march 2018 : 20:18.15
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public enum ChallengeScroll {

	ADAM("How many snakeskins are needed in order to craft 44 boots, 29 vambraces and 34 bandanas?", 666),
	ALI_THE_KEBAB_SELLER("How many coins would you need to purchase 133 kebabs from me?", 399),
	AMBASSADOR_ALVIJAR("Double the miles before the initial Dorgeshuun veteran.", 2505),
	ARETHA("32 - 5x = 22, what is x?", 2),
	AWOWOGEI("If I have 303 bananas, and share them between 31 friends evenly, only handing out full bananas. How many will I have left over?", 24),
	BARAEK("How many stalls are there in Varrock Square?", 5),
	BOLKOY("How many flowers are there in the clearing below this platform?", 13),
	BROTHER_KOJO("On a clock, how many times a day do the minute hand and the hour hand overlap?", 22),
	BROTHER_OMAD("What is the next number? 12, 13, 15, 17, 111, 113, 117, 119, 123....?", 129),
	BROTHER_TRANQUILITY("If I have 49 bottles of rum to share between 7 pirates, how many would each pirate get?", 7),
	BRUNDT_THE_CHIEFTAIN("How many people are waiting for the next bard to perform?", 5, 4),
	CAM_THE_CAMEL("How many items can carry water in Gielinor?", 6),
	CAPN_IZZY_NO_BEARDS("How many Banana Trees are there in the plantation?", 33),
	CAPTAIN_KHALED("How many fishing cranes can you find around here?", 5),
	CAPTAIN_TOBIAS("How many ships are there docked at Port Sarim currently?", 6),
	CAROLINE("How many fishermen are there on the fishing platform?", 11),
	CHARLIE_THE_TRAMP("How many coins would I have if I have 0 coins and attempt to buy 10 loaves of bread for 3 coins each?", 0),
	CLERRIS("If I have 1,000 blood runes, and cast 131 ice barrage spells, how many blood runes do I have left?", 738),
	COOK("How many cannons does Lumbridge Castle have?", 9),
	DARK_MAGE("How many rifts are found here in the abyss?", 13),
    TRAIBORN("How many air runes would I need to cast 630 wind waves?", 3150),
    WEIRD_OLD_MAN("SIX LEGS! All of them have 6! There are 25 of them! How many legs?", 150),
	DOCKMASTER("What is the cube root of 125?", 5),
	//DOMINIC_ONION("How many reward points does a herb box cost?", 9500), Nightmare zone not implemented so the question makes no sense on GadScape.
	DOOMSAYER("What is 40 divided by 1/2 plus 15?", 95),
	DREZEL("Please solve this for x: 7x - 28=21", 7),
	DUNSTAN("How much smithing experience does one receive for smelting a blurite bar?", 8),
	EDMOND("How many pigeon cages are there around the back of Jerico's house?", 3),
	ELUNED("A question on elven crystal math. I have 5 and 3 crystals, large and small respectively. A large crystal is worth 10,000 coins and a small is worth but 1,000. How much are all my crystals worth?", 53000),
	EOHRIC("King Arthur and Merlin sit down at the Round Table with 8 knights. How many degrees does each get?", 36),
	EVIL_DAVE("What is 333 multiplied by 2?", 666),
	FAIRY_GODFATHER("There are 3 inputs and 4 letters on each ring How many total individual fairy ring codes are possible?", 64),
	FATHER_AERECK("How many gravestones are in the church graveyard?", 19, 20),
	FLAX_KEEPER("If I have 1014 flax, and I spin a third of them into bowstring, how many flax do I have left?", 676),
	GABOOTY("How many buildings are in the village?", 11),
	GALLOW("How many vine patches can you find in this vinery?", 12),
	GNOME_BALL_REFEREE("What is 57 x 89 + 23?", 5096),
	GNOME_COACH("How many gnomes on the Gnome ball field have red patches on their uniforms?", 6),
	GUARD_VEMMELDO("How many magic trees can you find inside the Gnome Stronghold?", 3),
	HAZELMERE("What is 19 to the power of 3?", 6859),
	BRIMSTAIL("What is 19 to the power of 3?", 6859),
	HICKTON("How many ranges are there in Catherby?", 2),
	HORPHIS("On a scale of 1-10, how helpful is Logosia?", 1),
	KARIM("I have 16 kebabs, I eat one myself and then share the rest equally between 3 friends. How many do they have each?", 5),
	KAYLEE("How many chairs are there in the Rising Sun?", 18),
	KING_ROALD("How many bookcases are there in the palace library?", 24),
	LISSE_ISAAKSON("How many arctic logs are required to make a large fremennik round shield?", 2),
	MANDRITH("How many scorpions live under the pit?", 28),
	MARISI("How many cities form the Kingdom of Great Kourend?", 5),
	MARTIN_THWAIT("How many natural fires burn in Rogue's Den?", 2),
	MINER_MAGNUS("How many coal rocks are around here?", 8),
	NIEVE("How many farming patches are there in Gnome stronghold?", 2),
	STEVE("How many farming patches are there in Gnome stronghold?", 2),
	NURSE_WOONED("How many wounded soldiers are there in the camp?", 16),
	OLD_CRONE("What is the combined combat level of each species that live in Slayer tower?", 619),
	ONEIROMANCER("How many Suqah inhabit Lunar isle?", 25),
	ORACLE("If x is 15 and y is 3 what is 3x + y?", 48),
	ORONWEN("What is the minimum amount of quest points required to reach Lletya?", 20),
	OTTO_GODBLESSED("How many types of dragon are there beneath the whirlpool's cavern?", 2),
	OTTO_GODBLESSED_2("How many pyre sites are found around this lake?", 3),
	PROFESSOR_GRACKLEBONE("How many round tables can be found on this floor of the library?", 9),
    PROSPECTOR_PERCY("During a party, everyone shook hands with everybody else. There were 66 handshakes. How many people were at the party?", 12),
    RECRUITER("How many houses have a cross on the door?", 20),
    REGATH("What is -5 to the power of 2?", 25),
    SIGLI_THE_HUNTSMAN("What is the combined slayer requirement of every monster in the slayer cave?", 302),
    SIR_KAY("How many fountains are there within the grounds of Camelot castle?", 6),
    SIR_PERCIVAL("How many cannons are on this here castle?", 5),
    KING_PERCIVAL("How many cannons are on this here castle?", 5),
    SPIRIT_TREE("What is the next number in the sequence? 1, 11, 21, 1211, 111221, 312211", 13112221),
    SQUIRE("White Knights of Falador are stronger than the Black Knights of the Kinshra. 2 White Knights can handle 3 Kinshra. How many White Knights would we need against an army of 981 Kinshra?", 654),
    //Challenge scrolls of steps added from the OSRS wiki / RuneLite 1.13.3 (October 2026).
    MADAME_CALDARIUM("What is 3(5-3)?", 6),
    NICHOLAS("How many windows are in Tynan's shop?", 4),
    STRANGE_OLD_MAN("One pipe fills a barrel in 1 hour while another pipe can fill the same barrel in 2 hours. How many minutes will it take to fill the tank if both pipes are used?", 40),
    //The OSRS answer is 51 instead of 50 once Eagles' Peak is done; both are accepted.
    ZOO_KEEPER("How many animals in total are there in the zoo?", 50, 51),
    CAPTAIN_GINEA("1 soldier can deal with 6 lizardmen. How many soldiers do we need for an army of 678 lizardmen?", 113),
    TARIA("How many buildings are there in Rimmington?", 7),
    JETHICK("How many graves are there in the city graveyard?", 38),
    DRUNKEN_SOLDIER("If 13 Shayzien Soldiers kill 46 Lizardmen each in a day, how many Lizardmen have they killed in total in a single day?", 598),
    JARDRIC("What is 3 to the power of 0?", 1),
    WISE_OLD_MAN("How many bookcases are in the Wise Old Man's house?", 28),
    UGLUG_NAR("What is 19 to the power of 3?", 6859);

    private final String question, npcName;
    private final int answer;
    /**
     * Other answers that are accepted as well (OSRS answers that depend on the player's progress or changed over time).
     */
    private final int[] alternativeAnswers;

    ChallengeScroll(final String question, final int answer, final int... alternativeAnswers) {
        this.question = question;
        this.answer = answer;
        this.alternativeAnswers = alternativeAnswers;
        this.npcName = toString().toLowerCase();
    }

    public boolean isCorrect(final int value) {
        if (value == answer) {
            return true;
        }
        for (final int alternative : alternativeAnswers) {
            if (value == alternative) {
                return true;
            }
        }
        return false;
    }

    public String getQuestion() {
        return question;
    }

    public String getNpcName() {
        return npcName;
    }

    public int getAnswer() {
        return answer;
    }

}
