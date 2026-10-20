package devple.gow.engine.render;

/**
 * 층 2 그리기 단계 (47 문서 §7.4.2, 원작 0x160CC0) — 그림자 합성 0x160518 은 주 뷰 층 2 의 <b>불투명 단계 (t0 = 1)</b> 뒤,
 * <b>반투명 단계 (t0 = 2)</b> 앞에 끼어든다. 두 단계는 머티리얼 정렬 키 범위로 갈린다 (0x167900: t0 = 1 → [표[층][0] + 2, 표[층][2]),
 * t0 = 2 → [표[층][2], …)). 키 배정 0x155C40 → 0x155B50(마스크 bit 25 · 27 · 40) 이 첫 레이어 qword (렌더 플래그 | flags2 ≪ 32) 에
 * 그 비트가 있는 머티리얼을 반투명 단계로 보낸다: GS ALPHA A = 2 (0x42 뺄셈) · B = 2 (0x48 가산) · flags2 0x100 [확실: 디스어셈블].
 * 층 3 (flags2 0x8 → 머티리얼 bit 35, 블레이드 모델 0x150738(…, 2)) 은 층 2 가 다 끝난 뒤라 역시 합성 뒤.
 *
 * <p>엔진은 전역 단계 하나로 거른다: {@link #EARLY} = 합성 앞 (불투명 단계), {@link #LATE} = 합성 뒤 (반투명 단계 + 층 3),
 * {@link #ALL} = 거르지 않음 (뷰어·거울 반사 패스·하늘·이펙트). {@code Renderer.drawPasses} 와 {@code AnimatedModel.draw} 가 본다.
 */
public final class DrawStage {

	public static final int ALL = 0, EARLY = 1, LATE = 2;

	private static int current = ALL;

	private DrawStage() {
	}

	public static int current() {
		return current;
	}

	/** @return 이전 단계 (되돌리기용) */
	public static int set(int stage) {
		int prev = current;
		current = stage;
		return prev;
	}

	/** 이 단계에서 늦은 (합성 뒤) 파트 / 이른 파트를 건너뛰는가. */
	public static boolean skip(boolean late) {
		return current == EARLY && late || current == LATE && !late;
	}

	/**
	 * 머티리얼 첫 레이어가 반투명 단계 (또는 층 3·4) 인가 — 0x155B50 마스크 {@code 0x8005 << 25} (bit 25 · 27 · 40) 와
	 * 층 비트 (flags2 하위 5 비트, 0 이면 층 2 = 0x1534BC) 가 3 이상.
	 *
	 * @param renderFlags MAT 레이어 0x00 (bit 24–31 = GS ALPHA)
	 * @param flags2      MAT 레이어 0x04
	 */
	public static boolean lateMaterial(int renderFlags, int flags2) {
		long q = (renderFlags & 0xFFFFFFFFL) | ((long) flags2 << 32);
		if ((q & ((1L << 25) | (1L << 27) | (1L << 40))) != 0) {
			return true;
		}
		return (flags2 & 0x18) != 0; // 층 3 (bit 35) · 층 4 (bit 36)
	}
}
