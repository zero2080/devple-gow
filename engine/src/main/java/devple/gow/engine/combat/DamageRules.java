package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;

/**
 * 피해 배율 (10 문서 §5.3, Player_DamageScale 0x1DBD18). 표는 전부 TWK 에서 읽는다:
 * {@code /GlobGame/<난이도>/Damage Mult | AI Damage Mult}, {@code /Player/Weapon Level n/Dmg Mult | God Mode ...},
 * {@code /Player/Costume n/Damage Mult | AI Damage Mult}.
 */
public final class DamageRules {

	public static final String[] DIFFICULTY = {"Easy", "Normal", "Hard", "Impossible"};

	private final Tweaks tw;

	public DamageRules(Tweaks tw) {
		this.tw = tw;
	}

	/** 플레이어가 주는 피해 배율. */
	public float playerScale(int difficulty, int costume, int bladeLevel, boolean godMode) {
		return playerScale(difficulty, costume, bladeLevel, godMode, -1);
	}

	/**
	 * @param artemisStage 아르테미스의 검 장착 시 단계 0–2 (원작 player.flags&amp;0x20), 아니면 -1.
	 *                     장착 중에는 블레이드 레벨 배율 대신 {@code /Player/BerserkBlade/Stage n Damage Multiplier} (1.75/2.25/3.0)
	 */
	public float playerScale(int difficulty, int costume, int bladeLevel, boolean godMode, int artemisStage) {
		float m = tw.f("/GlobGame/" + DIFFICULTY[difficulty] + "/", "Damage Mult", 1f)
				* tw.f("/Player/Costume " + costume + "/", "Damage Mult", 1f);
		String wl = "/Player/Weapon Level " + bladeLevel + "/";
		if (godMode) {
			m *= tw.f(wl, "God Mode Dmg Mult", 2f);
		}
		if (artemisStage >= 0) {
			return m * tw.f("/Player/BerserkBlade/", "Stage " + artemisStage + " Damage Multiplier", 1.75f);
		}
		return m * tw.f(wl, "Dmg Mult", 1f);
	}

	/** 플레이어가 받는 피해 배율. */
	public float incomingScale(int difficulty, int costume, int bladeLevel, boolean godMode) {
		float m = tw.f("/GlobGame/" + DIFFICULTY[difficulty] + "/", "AI Damage Mult", 1f)
				* tw.f("/Player/Costume " + costume + "/", "AI Damage Mult", 1f);
		if (godMode) {
			m *= tw.f("/Player/Weapon Level " + bladeLevel + "/", "God Mode Enmy Dmg Mult", 1f);
		}
		return m;
	}
}
