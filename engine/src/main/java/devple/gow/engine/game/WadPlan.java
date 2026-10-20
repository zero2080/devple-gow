package devple.gow.engine.game;

import devple.gow.tools.content.Region;
import java.util.ArrayList;
import java.util.List;

/**
 * 현재 상태에서 메모리에 있어야 할 WAD 목록 (11 문서 §6.3 힙 배치 규칙).
 * <pre>
 * Perm   : R_PERMK                                       (항상)
 * Shell  : R_SHELLK                                      (셸 상태에서만)
 * Hero   : R_HERO&lt;costume&gt;, R_SAVEPT                 (게임 중)
 * Upgrade: R_WPN&lt;costume&gt;_&lt;blade&gt;, 보유 마법 R_LGHTN&lt;n&gt; ...  (레벨 변경 시 교체)
 * Levels : 현재 레벨 WAD (+ 워프용 다음 레벨)
 * Slots  : 레벨 WadInfo 가 지정한 적 리소스 (레벨 로더가 추가)
 * </pre>
 */
public final class WadPlan {

	private static final String[] MAGIC_WAD = {null, "R_LGHTN", "R_MEDHD", "R_THBLT", "R_RDEAD", "R_BRSRK"};

	private WadPlan() {
	}

	public static List<String> required(GameState state, PlayerState p, String level, String nextLevel) {
		List<String> out = new ArrayList<>();
		out.add(Region.current().perm().replace(".WAD", ""));
		if (!state.inLevel() && state != GameState.LEVEL_LOADING && state != GameState.LOAD_APPLY) {
			out.add(Region.current().shell().replace(".WAD", ""));
			return out;
		}
		out.add("R_HERO" + p.costume);
		out.add("R_SAVEPT");
		out.add("R_WPN" + p.costume + "_" + p.level.get(Weapon.BLADES));
		Weapon[] ws = Weapon.values();
		for (int i = 1; i < ws.length; i++) {
			if (p.owned(ws[i])) {
				out.add(MAGIC_WAD[i] + p.level.get(ws[i]));
			}
		}
		if (level != null) {
			out.add(level);
		}
		if (nextLevel != null) {
			out.add(nextLevel);
		}
		return out;
	}
}
