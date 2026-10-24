package devple.gow.engine.game;

/**
 * 스크립트 화면 페이드 (원작 상태 0x303D50: +0x04..+0x0C 색 rgb, +0x10 현재 프레임, +0x14 전체 프레임, +0x18 상태 0 없음 / 1 어둡게 / 2 밝게)
 * [확실: 디스어셈블].
 * <ul>
 *   <li>FadeOut(r, g, b, 초) 0x1AFA20: 상태가 1 이면 무시. 색을 넣고 n = 초 / (1/60), 전체가 있었으면 현재 = (전체 − 현재)·n / 전체 (정수, 진행을 뒤집어 이어감)
 *       아니면 0, 전체 = n, 상태 = 1.</li>
 *   <li>FadeIn(초) 0x1AFAD4: 상태가 1 일 때만. 상태 = 2, 같은 식으로 현재·전체.</li>
 *   <li>매 프레임 0x1AF648: 상태 0 이면 없음. p = 전체 ? 현재/전체 : 1, 현재 + 1, 알파 = 상태 1 이면 p 아니면 1 − p → 색 사각형 0x2C8F50 (r, g, b, 알파).
 *       현재 &gt; 전체면 (상태 2 면 상태 0) 현재 = 전체 = 0 — 어둡게 끝나면 상태 1·전체 0 이라 p = 1 로 계속 덮여 있다 (다음 FadeIn 까지).</li>
 *   <li>레벨 스크립트 초기화 0x1AF600 이 전부 0 (엔진: 레벨마다 새 GameScene).</li>
 * </ul>
 * 예: ATHN03AA 오라클 영상 a03Mp02MPEG — idle 볼륨 진입 → FadeOut(0, 0, 0, 0.4) → 0.4 초 뒤 PlayMovie → 영상 끝 (501) FadeIn(1).
 */
public final class ScreenFade {

	/** 원작 프레임 길이 0x29D318 (1/60 초). */
	static final float FRAME = 0.016666668f;

	private float r, g, b;
	private int cur, total, state;
	/** 지금 덮는 색 (r, g, b, 알파) — 0x2C8F50. */
	private final float[] color = new float[4];

	/** FadeOut (0x1AFA20). */
	public void fadeOut(float r, float g, float b, float seconds) {
		if (state == 1) {
			return;
		}
		this.r = r;
		this.g = g;
		this.b = b;
		restart((int) (seconds / FRAME));
		state = 1;
	}

	/** FadeIn (0x1AFAD4): 어둡게 하는 중·끝난 뒤에만. */
	public void fadeIn(float seconds) {
		if (state != 1) {
			return;
		}
		state = 2;
		restart((int) (seconds / FRAME));
	}

	private void restart(int n) {
		cur = total != 0 ? (total - cur) * n / total : 0;
		total = n;
	}

	/** 한 프레임 (60Hz 틱) 진행 (0x1AF648). */
	public void tick() {
		if (state == 0) {
			color[3] = 0;
			return;
		}
		float p = total != 0 ? (float) cur / total : 1f;
		cur++;
		color[0] = r;
		color[1] = g;
		color[2] = b;
		color[3] = state == 1 ? p : 1 - p;
		if (total < cur) {
			if (state == 2) {
				state = 0;
			}
			cur = 0;
			total = 0;
		}
	}

	/** 덮는 색 (r, g, b, 알파 0..1). 알파 0 이면 그리지 않는다. */
	public float[] color() {
		return color;
	}

	/** 0 없음 / 1 어둡게 (끝났으면 계속 덮음) / 2 밝게. */
	public int state() {
		return state;
	}
}
