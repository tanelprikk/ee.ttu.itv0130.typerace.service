package ee.ttu.itv0130.typerace.service.data_service.objects;

public class RoundScore {
	private Integer index;
	private Long playerTimeMillis;
	private Long opponentTimeMillis;
	private String word;
	private int playerScore;
	private int opponentScore;
	private boolean didWin = false;

	public void setIndex(Integer index) {
		this.index = index;
	}

	public Integer getIndex() {
		return index;
	}

	public void setDidWin(boolean didWin) {
		this.didWin = didWin;
	}

	public boolean didWin() {
		return didWin;
	}

	public void setPlayerTimeMillis(Long playerTimeMillis) {
		this.playerTimeMillis = playerTimeMillis;
	}

	public Long getPlayerTimeMillis() {
		return playerTimeMillis;
	}

	public void setOpponentTimeMillis(Long opponentTimeMillis) {
		this.opponentTimeMillis = opponentTimeMillis;
	}

	public Long getOpponentTimeMillis() {
		return opponentTimeMillis;
	}

	public void setWord(String word) {
		this.word = word;
	}

	public String getWord() {
		return word;
	}

	public void setPlayerScore(int playerScore) {
		this.playerScore = playerScore;
	}

	public Integer getPlayerScore() {
		return playerScore;
	}

	public void setOpponentScore(int opponentScore) {
		this.opponentScore = opponentScore;
	}

	public Integer getOpponentScore() {
		return opponentScore;
	}

	public RoundScore forOpponent() {
		RoundScore opponentRoundScore = new RoundScore();
		opponentRoundScore.setDidWin(!didWin);
		opponentRoundScore.setWord(word);
		opponentRoundScore.setOpponentTimeMillis(playerTimeMillis);
		opponentRoundScore.setPlayerTimeMillis(opponentTimeMillis);
		opponentRoundScore.setOpponentScore(playerScore);
		opponentRoundScore.setPlayerScore(opponentScore);
		return opponentRoundScore;
	}
}
