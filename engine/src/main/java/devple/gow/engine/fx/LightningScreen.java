package devple.gow.engine.fx;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.fx.EnvParams;

/**
 * 포세이돈의 분노 화면 효과 (SCR_Lightning 0x1948F0 → 0x195BA8 시작 / 0x195C90 매 프레임, 35 문서 §4.6) [확실: 코드].
 * <ul>
 *   <li>시작 (0x195BA8, 인자 = 단계 표 +0x36 Fog Template, +0x30 Bright / +0x32 Dark Camera Filter, +0x34 Flash Freq (모두 short),
 *       f12 지속 = 0): Fog Template ≠ −1 이면 {@code /TweakTemplates/Fog/NNN/} (0x29F320 목록, 0x182D50 = 번호로 조회) 의
 *       색·CentreZ·Blend Mode·BackPlane_A 를 전용 안개 관리자 0x2FF730 에 넣는다 (0x125970, 층 안개 비트 0x2 끔, 안개 텍스처 없음).
 *       플래시 타이머 0x3368C4 = 0.</li>
 *   <li>매 프레임 (0x195C90): 타이머 −= 1/60. ≤ 0 이면 카메라(0x29F16C) +0x910 = Bright 필터 (≠ −1 일 때), 타이머 += 1/Flash Freq;
 *       아니면 +0x910 = Dark 필터 (≠ −1 일 때). Fog Template 이 있으면 환경 +0x110 = 0x2FF730 (렌더 0x1281C8 끝에서 다시 레벨 안개
 *       +0x80 으로 되돌림 → <b>스크립트가 도는 프레임에만</b> 안개 대체), 관리자 +0x84 = 현재 프레임 (0x125BE0 의 유효 검사).</li>
 *   <li>+0x910 은 렌더 0x1EFA14 가 읽는다: ≥ 0 이면 그 CameraFilter 템플릿 (0x337B74 목록), 아니면 현재 카메라의 기본 필터.
 *       아무도 −1 로 되돌리지 않는다 (초기화 0x1EDC40 만).</li>
 * </ul>
 * R_PERMK 값: 세 단계 모두 Fog Template 0 ("360 Lightning": 색 (0.35, 0.25, 0.2), Fog_A 0.6, CentreZ 16500, Blend Mode 2 = 감산,
 * BackPlane_A −1), Bright 필터 0 ("None": 색 전부 0, Blend Mode 1 = 가산 0x48 → 보이는 변화 없음), Dark −1 (쓰지 않음), Flash Freq 5.
 * 그래서 눈에 보이는 것은 안개 대체뿐이다. 카메라 필터 렌더 (0x162008) 는 엔진에 없고 값만 기록한다.
 */
public final class LightningScreen {

	private final Tweaks tw;
	private boolean on;
	private float flashTimer;
	private int bright = -1, dark = -1, flashFreq = 1;
	private EnvParams.Fog fog;
	/** 카메라 +0x910 (CameraFilter 템플릿 번호, −1 = 카메라 기본). 원작처럼 되돌리지 않는다. */
	private int filter = -1;
	/** Bright 필터를 건 횟수 (테스트). */
	public int flashes;

	public LightningScreen(Tweaks tw) {
		this.tw = tw;
	}

	/**
	 * 한 틱.
	 *
	 * @param script SCR_Lightning 이 도는지 ({@link devple.gow.engine.combat.Magic#lightningScript()})
	 * @param stage  포세이돈 단계 0..2
	 */
	public void update(boolean script, int stage, float dt) {
		if (!script) {
			on = false;
			fog = null;
			return;
		}
		if (!on) {
			on = true;
			String sp = "Stage " + stage + " ";
			String n = "/Player/Lightning/";
			bright = tw.i16(n, sp + "Bright Camera Filter", -1);
			dark = tw.i16(n, sp + "Dark Camera Filter", -1);
			flashFreq = Math.max(1, tw.i16(n, sp + "Flash Freq", 1));
			int tpl = tw.i16(n, sp + "Fog Template", -1);
			fog = tpl < 0 ? null : fogTemplate(tw, tpl);
			flashTimer = 0;		}
		flashTimer -= dt;
		if (flashTimer <= 0) {
			if (bright != -1) {
				filter = bright;
				flashes++;
			}
			flashTimer += 1f / flashFreq;
		} else if (dark != -1) {
			filter = dark;
		}
	}

	/** 이번 프레임의 안개 대체 (없으면 null) → {@link FxEnvironment#setFogOverride}. */
	public EnvParams.Fog fog() {
		return fog;
	}

	/** 카메라 필터 대체 번호 (−1 = 기본). */
	public int filter() {
		return filter;
	}

	/**
	 * {@code /TweakTemplates/Fog/NNN/} (생성자 0x20D7F0: Fog_R/G/B/A f32 → +0x20, CentreZ int → +0x50, Blend Mode 열거 → +0x54,
	 * BackPlane_A int → +0x58). 없으면 null.
	 */
	public static EnvParams.Fog fogTemplate(Tweaks tw, int n) {
		String node = String.format("/TweakTemplates/Fog/%03d/", n);
		if (tw.raw(node, "CentreZ").isEmpty()) {
			return null;
		}
		float[] c = {tw.f(node, "Fog_R", 0), tw.f(node, "Fog_G", 0), tw.f(node, "Fog_B", 0), tw.f(node, "Fog_A", 0)};
		return new EnvParams.Fog(tw.s(node, "Instance Name", node), c, tw.i(node, "CentreZ", 0), new float[2], 0, 1, null,
				tw.i(node, "Blend Mode", 0) & 0xFF, tw.i(node, "BackPlane_A", -1));
	}
}
