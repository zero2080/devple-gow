package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;

/**
 * SCR_GrowTimer (기술 SCRIPT 해시 0x01EE9AD5, 등록 0x190210 → 시작 0x190030 [확실: 디스어셈블]) — 케르베로스 새끼 (R_CEP) 가 자라 어른 (R_CEA) 이 된다.
 * 새끼의 생성 기술 SPAWNVOMIT 502 · SPAWNVOMITLAND 506 이 idx 85 로 건다 (43 §40).
 * <ol>
 *   <li>시작 0x190030: GrowChar 템플릿 (idx) 을 찾아 남은 시간 = #452d9a3d + 난수 × #91fa5ec0 (085 "PuppyGrow": 30 + 0), 갱신 0x18FDD8 를 단다.</li>
 *   <li>갱신 0x18FDD8 · 0x18F440 (매 프레임 1/60 씩): 남은 시간 &lt; #f469f079 (2 초) 이면 "FX Name" (goCEPtranSpark) 을 몸에 붙이고 세기를
 *       #18174fd6 + (1 − 남은/2) × (1 − #18174fd6) 로 올린다. 남은 시간 &lt; 0 이면 지금 기술을 끊고 (0x209918) 액션 #f0591165 (u8, 30 TRANSFORM)
 *       을 요청 (0x209378) 하고 변신 단계로.</li>
 *   <li>변신 0x18F798: p = 지금 기술 진행률 (기술이 없으면 1), GO 행렬의 세 축에 1 + p × "Adult Scale" (1.5) 을 곱한다. p &gt; 0.95 이면
 *       엔티티 #556a7bc2 ("GrowAdult") 를 0x1A90D0 으로 읽어 자리 (+2) 만 새끼의 것으로 바꾸고, 새끼를 지운 뒤 (0x1DFFA8) 정규화한 새끼 행렬에
 *       0x1A9298 로 만든다 (MaximumCount 검사 · 만든 수 증가 없음, Action 은 돈다).</li>
 *   <li>새끼가 그 전에 죽으면 (핸들 무효) 이펙트를 끄고 끝.</li>
 * </ol>
 */
public final class GrowTimer {

	public static final int SCR_GROW_TIMER = 0x01EE9AD5;
	/** 0x18F798: 변신 기술 진행률이 이 값을 넘으면 어른으로 바꾼다 (0x3F733333). */
	public static final float SWAP_PROGRESS = 0.95f;

	/** GrowChar 템플릿 (0x18F240 의 필드 기본값 · 085 PuppyGrow 데이터). */
	public record Def(float time, float timeRand, float sparkBase, float sparkLead, float adultScale, String entity, int action, String fx) {

		/** /TweakTemplates/GrowChar/NNN/ — 없으면 null. */
		public static Def of(Tweaks tw, int idx) {
			String n = tw.findNode(String.format("/TweakTemplates/GrowChar/%03d/", idx));
			if (n == null) {
				return null;
			}
			return new Def(f(tw, n, 0x452D9A3D, 10f), f(tw, n, 0x91FA5EC0, 5f), f(tw, n, 0x18174FD6, 0.25f), f(tw, n, 0xF469F079, 2f),
					tw.f(n, "Adult Scale", 3f), tw.sHash(n, 0x556A7BC2, "GrowAdult"), tw.rawHash(n, 0xF0591165).orElse(0) & 0xFF,
					tw.s(n, "FX Name", "goCerGrow"));
		}

		private static float f(Tweaks tw, String n, int hash, float def) {
			var v = tw.rawHash(n, hash);
			return v.isPresent() ? Float.intBitsToFloat(v.getAsInt()) : def;
		}
	}

	public enum Phase { TIMER, TRANSFORM, DONE }

	public final Def def;
	private float left;
	private Phase phase = Phase.TIMER;

	/** @param rand01 0x13E600 난수 [0, 1) */
	public GrowTimer(Def def, float rand01) {
		this.def = def;
		this.left = def.time() + rand01 * def.timeRand();
	}

	public Phase phase() {
		return phase;
	}

	public float left() {
		return left;
	}

	/** 한 프레임 (0x18F440: 1/60 고정). 변신 액션을 요청할 프레임이면 참. */
	public boolean tick() {
		if (phase != Phase.TIMER) {
			return false;
		}
		left -= 1f / 60f;
		if (left < 0) {
			phase = Phase.TRANSFORM;
			return true;
		}
		return false;
	}

	/** 불꽃 이펙트 세기 (0x18F664–0x18F680), 아직 아니면 −1. */
	public float spark() {
		if (left >= def.sparkLead()) {
			return -1;
		}
		if (left < 0) {
			return 1;
		}
		return def.sparkBase() + (1 - left / def.sparkLead()) * (1 - def.sparkBase());
	}

	/** 변신 중 몸 배율 (0x18F9F8–0x18FA08): 1 + p × Adult Scale. */
	public float scale(float progress) {
		return 1 + progress * def.adultScale();
	}

	/** 변신 단계에서 어른으로 바꿀 때인지 — 바꾸면 끝 상태로. */
	public boolean swap(float progress) {
		if (phase == Phase.TRANSFORM && progress > SWAP_PROGRESS) {
			phase = Phase.DONE;
			return true;
		}
		return false;
	}
}
