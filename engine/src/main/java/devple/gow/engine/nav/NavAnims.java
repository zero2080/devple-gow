package devple.gow.engine.nav;

import devple.gow.engine.game.Tweaks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 원작 TWK {@code /Animation/goHero/Navigation/} 표 (R_HERO0): 동작 키 → 애니 이름 (act 또는 블렌드 그룹).
 * 표가 없으면 R_HERO0 의 값을 기본으로 쓴다. 블렌드 그룹(bClimb = wClimbBlend0..3, balWalk = balanceWblend0..4)은
 * 첫 act 로 재생한다 [근사: 블렌드 축 보간 없음].
 */
public final class NavAnims {

	public static final String NODE = "/Animation/goHero/Navigation/";
	private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

	static {
		String[] kv = {
				"ANIWallHang", "WallhangI", "ANIWallHangCatch", "WallhangCatch", "ANIWallHangR", "WallhangR", "ANIWallHangL", "WallhangL",
				"ANIWallPullup", "wallpullup", "ANIWallCatch", "wallcatch", "ANIWJump", "jumpwall",
				"ANIClimbIdle", "wallclimbidle", "ANIClimbAL", "wallClimbAL", "ANIClimbAR", "wallClimbAR",
				"ANISwim", "swim", "ANISwimIdle", "swimidle", "ANITread", "tread",
				"ANIBalanceWalk", "balanceWblend1", "ANIBalanceCatch", "balancebeamcatch",
				"ANIRope", "ropeidle", "ANIRopeU", "ropeUp", "ANIRopeD", "ropeDown", "ANIRopeSWStart", "ropeswingstart",
				"ANIRopeSW1", "ropeswingmidtofrnt", "ANIRopeSW2", "ropeswingfrnttomid", "ANIRopeSW3", "ropeswingmidtoback",
				"ANIRopeSW4", "ropeswingbacktomid", "ANIRopeIdle", "ropeswingidle", "ANIRSlide", "ropeslide",
				"ANIBRope", "ropehohi", "ANIBRopeF", "ropehoh", "ANIZipLine", "zipline",
				"ANILadder", "ladderUp", "ANILadderU", "ladderUp", "ANILadderD", "ladderDown", "ANILDis1", "dismount1", "ANILDis2", "dismount2",
				"ANIPushI", "pushIdle", "ANIPushF", "Push", "ANIPushB", "Pull", "ANIPushL", "PullL", "ANIPushR", "PullR",
				"ANIStartKickBox", "KickBox1", "ANIKickBoxIdle", "KickBox3", "ANIKickBox", "KickBox2",
				"ANICrankIdle", "crankIdle", "ANICrankPush", "Push", "ANICrankPull", "Pull", "ANILever", "leverpull",
				"ANICSM1", "chestGrab", "ANICSM2", "chestOpen", "ANIFall", "fallv", "ANILand", "land"};
		for (int i = 0; i < kv.length; i += 2) {
			DEFAULTS.put(kv[i], kv[i + 1]);
		}
	}

	/** 벽 오르기 방향별 블렌드 act (그룹 bClimb): 위 / 오른쪽 / 아래 / 왼쪽 [추정: 그룹 안 순서]. */
	public static final String[] CLIMB = {"wClimbBlend0", "wClimbBlend1", "wClimbBlend2", "wClimbBlend3"};

	private final Map<String, String> map = new LinkedHashMap<>(DEFAULTS);

	public NavAnims() {
	}

	/** TWK 표로 덮어쓰기 (블렌드 그룹 이름은 기본값의 act 를 유지). */
	public NavAnims(Tweaks t) {
		if (t == null) {
			return;
		}
		for (String k : DEFAULTS.keySet()) {
			String v = t.s(NODE, k, null);
			if (v != null && !v.isEmpty() && !v.equalsIgnoreCase("balwalk") && !v.equalsIgnoreCase("bclimb")) {
				map.put(k, v);
			}
		}
	}

	/** 동작 키 (예 "ANIWallHang") → 애니 이름. */
	public String get(String key) {
		return map.getOrDefault(key, key);
	}
}
