package devple.gow.engine.game;

import devple.gow.tools.progress.UpgradeTable;
import devple.gow.tools.wad.WadFile;

import java.util.EnumMap;
import java.util.Map;

/**
 * 성장 시스템 수치. 전부 원본 데이터에서 읽는다 (11 문서 §1, §2).
 * <ul>
 *   <li>최대 HP/MP: TWK {@code /Player/Max Hit Points Level n}, {@code Max Magic Level n}</li>
 *   <li>업그레이드 비용: FLP_HUDK 스크립트 상수 ({@link UpgradeTable})</li>
 *   <li>오브 배율: TWK {@code /Player/Costume n/Health|Magic|Weapon Orb Mult}</li>
 * </ul>
 */
public final class GameRules {

	public static final int MAX_RED_ORBS = 60000;
	public static final int MAX_SHARDS = 18;
	public static final int HP_LEVELS = 5;
	public static final int MP_LEVELS = 6;

	private final float[] maxHp = new float[HP_LEVELS];
	private final float[] maxMp = new float[MP_LEVELS];
	private final Map<Weapon, int[]> costs = new EnumMap<>(Weapon.class);
	private final int shardsPerLevel;
	private final Tweaks tweaks;

	public GameRules(Tweaks tweaks, UpgradeTable upgrades) {
		this.tweaks = tweaks;
		for (int i = 0; i < HP_LEVELS; i++) {
			maxHp[i] = tweaks.f("/Player/", "Max Hit Points Level " + i, 100 + 25 * i);
		}
		for (int i = 0; i < MP_LEVELS; i++) {
			maxMp[i] = tweaks.f("/Player/", "Max Magic Level " + i, i == 0 ? 0 : 75 + 25 * i);
		}
		// 정수형 TWK 값은 하위 바이트만 유효할 수 있음 (06 문서)
		shardsPerLevel = tweaks.i("/Player/", "Shards Per Meter Level", 6) & 0xFF;
		for (Weapon w : Weapon.values()) {
			int[] c = new int[w.maxLevel];
			Map<Integer, Integer> m = upgrades.costs.getOrDefault(w.key, Map.of());
			for (int l = 0; l < w.maxLevel; l++) {
				c[l] = m.getOrDefault(l, Integer.MAX_VALUE);
			}
			costs.put(w, c);
		}
	}

	public static GameRules load(WadFile permk) {
		Tweaks t = new Tweaks();
		t.load(permk);
		return new GameRules(t, UpgradeTable.fromWad(permk));
	}

	public float maxHp(int level) {
		return maxHp[Math.min(level, HP_LEVELS - 1)];
	}

	public float maxMp(int level) {
		return maxMp[Math.min(level, MP_LEVELS - 1)];
	}

	/** 현재 레벨 → 다음 레벨 비용. 최대 레벨이면 MAX_VALUE. */
	public int upgradeCost(Weapon w, int level) {
		int[] c = costs.get(w);
		return level < c.length ? c[level] : Integer.MAX_VALUE;
	}

	public int shardsPerLevel() {
		return shardsPerLevel;
	}

	/** 오브 종류(0 체력, 1 마법, 2 붉은 오브)별 코스튬 배율. */
	public float orbMult(int costume, int orbType) {
		String key = switch (orbType) {
			case 0 -> "Health Orb Mult";
			case 1 -> "Magic Orb Mult";
			default -> "Weapon Orb Mult";
		};
		return tweaks.f("/Player/Costume " + costume + "/", key, 1f);
	}

	public Tweaks tweaks() {
		return tweaks;
	}
}
