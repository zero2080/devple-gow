package devple.gow.engine.input;

import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.Set;

/**
 * -Dgow.bot: 시연 녹화용 자동 플레이 (원작에 없음). 가장 가까운 적에게 걸어가 □□□△ 연타, 네 번째 묶음마다 ○ 잡기 뒤 □.
 * 적이 없으면 천천히 원을 그리며 걷는다 (해치 위 등 볼륨 안에 서 있으면 진행이 보류되는 스크립트가 있다 — ATHN01A HatchZone). 틱마다 {@link #steer} 후 영웅 입력으로 넘긴다.
 */
public final class BotInput extends Input {

	private static final float NEAR = 110f;
	/** 한 묶음 (초, 버튼): 0.3 초 간격 누름. */
	private static final PadButton[] COMBO = {PadButton.SQUARE, PadButton.SQUARE, PadButton.SQUARE, PadButton.TRIANGLE};
	private static final PadButton[] GRAB = {PadButton.CIRCLE, PadButton.SQUARE, PadButton.SQUARE, PadButton.SQUARE};
	private static final float GAP = 0.3f, PAUSE = 0.5f;

	private final Set<PadButton> down = EnumSet.noneOf(PadButton.class);
	private final Set<PadButton> prev = EnumSet.noneOf(PadButton.class);
	private float lx, ly;
	private float clock, wander;
	private int combos;

	public BotInput(long window) {
		super(window);
	}

	/** target = 가장 가까운 살아 있는 적 (없으면 null). */
	public void steer(Vector3f hero, Vector3f target, float camYaw, float dt) {
		prev.clear();
		prev.addAll(down);
		down.clear();
		lx = ly = 0;
		wander += dt;
		if (target == null) {
			clock = 0;
			lx = (float) Math.sin(wander * 0.4f);
			ly = (float) Math.cos(wander * 0.4f);
			return;
		}
		float dx = target.x - hero.x, dz = target.z - hero.z;
		float d = (float) Math.hypot(dx, dz);
		float a = (float) Math.atan2(-dx, -dz) - camYaw; // HeroController: 정면 = (-sin yaw, -cos yaw), 스틱 각 = atan2(-sx, sy)
		if (d > NEAR && clock == 0) {
			lx = (float) -Math.sin(a);
			ly = (float) Math.cos(a);
			return;
		}
		PadButton[] seq = combos % 4 == 3 ? GRAB : COMBO;
		int i = (int) (clock / GAP);
		if (i < seq.length) {
			if (clock - i * GAP < 0.08f) {
				down.add(seq[i]);
			}
			if (i == 0) { // 첫 누름 동안 적 쪽으로 기울여 방향을 맞춘다
				lx = (float) -Math.sin(a) * 0.5f;
				ly = (float) Math.cos(a) * 0.5f;
			}
		}
		clock += dt;
		if (clock >= seq.length * GAP + PAUSE) {
			clock = 0;
			combos++;
		}
	}

	@Override
	public void poll() {
	}

	@Override
	public boolean held(PadButton b) {
		return down.contains(b);
	}

	@Override
	public boolean pressed(PadButton b) {
		return down.contains(b) && !prev.contains(b);
	}

	@Override
	public boolean released(PadButton b) {
		return !down.contains(b) && prev.contains(b);
	}

	@Override
	public float leftX() {
		return lx;
	}

	@Override
	public float leftY() {
		return ly;
	}

	@Override
	public float rightX() {
		return 0;
	}

	@Override
	public float rightY() {
		return 0;
	}
}
