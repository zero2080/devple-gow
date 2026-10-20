package devple.gow.engine.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 그림자 GS 합성 (47 문서 §7.4) · 지움 색 (43 문서 §13.4) 의 원작 값. */
class ShadowCompositeTest {

	@Test
	void stencilCountMatchesGreenChannelBit7() {
		// 원작: 초록 0x7F 에서 시작, 면마다 ±1 (8 비트 감김), DATE 가 bit 7 을 본다 → Σ ∈ [1, 128] 만 그림자
		assertFalse(ShadowRenderer.shadowed(0), "볼륨 밖 (앞 −1 · 뒤 +1 상쇄)");
		assertTrue(ShadowRenderer.shadowed(1), "볼륨 하나 안");
		assertTrue(ShadowRenderer.shadowed(2), "겹친 볼륨 둘 — 한 번만 어둡게");
		assertTrue(ShadowRenderer.shadowed(128));
		assertFalse(ShadowRenderer.shadowed(-1), "음수 합은 그림자가 아니다 (≠ 0 판정과 다름)");
		assertFalse(ShadowRenderer.shadowed(129), "8 비트 감김");
		assertEquals(0x7F, ShadowRenderer.STENCIL_BASE);
		assertEquals(0x80, ShadowRenderer.SHADOW_BIT);
	}

	@Test
	void darkenIsSubtractive38() {
		// 0x29CF60 +0x08 = 0x80262626 (RGB 38, A 0x80), ALPHA 0x42 = Cd − Cs·As → 곱이 아니라 뺄셈, 0 에서 멈춤
		assertEquals(38, ShadowRenderer.SUBTRACT_8BIT);
		assertEquals(162, ShadowRenderer.composite8(200, true));
		assertEquals(0, ShadowRenderer.composite8(20, true));
		assertEquals(200, ShadowRenderer.composite8(200, false));
		assertEquals(38 / 255f, ShadowRenderer.SUBTRACT, 1e-6f);
	}

	@Test
	void clearColourIsBlack() {
		// 0x29CF64 지움 노드: 색 (0, 0, 0, 0) — 0x15F638 이 만든 뒤 다른 쓰기 없음
		assertEquals(0f, Renderer.CLEAR_R);
		assertEquals(0f, Renderer.CLEAR_G);
		assertEquals(0f, Renderer.CLEAR_B);
	}
}
