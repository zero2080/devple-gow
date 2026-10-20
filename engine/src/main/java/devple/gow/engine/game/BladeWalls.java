package devple.gow.engine.game;

import devple.gow.engine.combat.HitVolumes;
import devple.gow.engine.nav.NavWorld;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.Supplier;

/**
 * 블레이드 벽 질의 (0x1FAFC8 → 0x11E190, 45 문서 §3.8) 의 충돌 세계 [확실: 마스크·분기 / 추정: 엔트리 범주]:
 * <ul>
 *   <li>정적 시트 (현재 + 다른 슬롯 레벨) — 맞은 개체 없음 → bit31 (당김). 범주 {@link BladeChain#CAT_WORLD} [추정: 시트 엔트리 범주].</li>
 *   <li>CDV GO 몸체 (문·발판·정박한 배, 이 레벨 + 다른 슬롯 레벨) — 소유 GO +0xF4 = 0 → 엔트리 범주 0x80000000 (0x11D608) 이지만
 *       GO +0xF4 자체는 bit 31 이 아니므로 (0x1FB350 bgez) 첫 질의는 불꽃만, 둘째 질의 (0x80000000) 가 같은 몸체를 맞혀 당긴다.
 *       (상자 0x20FC60 / 0x210120 / 0x212C0C 처럼 +0xF4 에 0x80000000 을 직접 세우는 GO 만 첫 질의로 당긴다 — 엔진 상자에는 CDV 몸체가 없음.)</li>
 *   <li>살아 있는 적의 피격 구 — 범주 1|2 (AI 초기화 0x1D0890) → 첫 질의만. 불꽃 = 적 살 재질 SheetFX 의 Slice Hit (예 MFX 500 Flesh goFleshSlice),
 *       당김은 둘째 질의가 그 뒤 벽을 맞힐 때만.</li>
 * </ul>
 * 영웅은 질의 +0x14 로 빠진다.
 */
public final class BladeWalls implements BladeChain.Walls {

	private final Supplier<NavWorld> world;
	private final Supplier<List<HitVolumes>> creatures;

	public BladeWalls(Supplier<NavWorld> world, Supplier<List<HitVolumes>> creatures) {
		this.world = world;
		this.creatures = creatures;
	}

	@Override
	public BladeChain.WallHit cast(Vector3f from, Vector3f to, long exclude, int types) {
		NavWorld w = world.get();
		float bestT = Float.MAX_VALUE;
		BladeChain.WallHit best = null;
		if (w != null && (types & BladeChain.CAT_WORLD) != 0) {
			NavWorld.Contact s = w.staticContact(from, to, exclude);
			if (s != null) {
				bestT = s.t();
				best = new BladeChain.WallHit(s.point(), s.normal(), s.sheetFx(), true);
			}
			NavWorld.Contact b = w.bodyContact(from, to, exclude);
			if (b != null && b.t() < bestT) {
				bestT = b.t();
				best = new BladeChain.WallHit(b.point(), b.normal(), b.sheetFx(), false);
			}
		}
		if (creatures != null && (types & BladeChain.CAT_CREATURE) != 0) {
			for (HitVolumes hv : creatures.get()) {
				float[] h = hv == null ? null : hv.segmentCast(from, to);
				if (h != null && h[0] < bestT) {
					bestT = h[0];
					best = new BladeChain.WallHit(new Vector3f(h[1], h[2], h[3]), new Vector3f(h[4], h[5], h[6]), (int) h[7], false);
				}
			}
		}
		return best;
	}
}
