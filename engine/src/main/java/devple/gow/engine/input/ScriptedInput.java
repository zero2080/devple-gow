package devple.gow.engine.input;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 자동 검증용 입력 재생. 스텝 = (시작 시각, 누르고 있을 시간, 버튼). 틱마다 {@link #advance(float)}.
 */
public final class ScriptedInput extends Input {

	public record Step(float at, float hold, PadButton button) {
	}

	/** 스틱 입력 구간: right = 오른쪽 스틱 여부. */
	public record Stick(float at, float hold, boolean right, float x, float y) {
	}

	private final List<Stick> sticks = new java.util.ArrayList<>();
	private float lx, ly, rx, ry;

	public ScriptedInput stick(Stick s) {
		sticks.add(s);
		return this;
	}

	private final List<Step> steps;
	private final Set<PadButton> down = EnumSet.noneOf(PadButton.class);
	private final Set<PadButton> prev = EnumSet.noneOf(PadButton.class);
	private float time;
	/** 반복 주기 (초, 0 = 반복 없음): 장시간 측정용 (48 문서). */
	private float loop;

	public ScriptedInput loop(float period) {
		loop = period;
		return this;
	}

	public ScriptedInput(long window, List<Step> steps) {
		super(window);
		this.steps = steps;
	}

	public void advance(float dt) {
		time += dt;
		float time = loop > 0 ? this.time % loop : this.time;
		prev.clear();
		prev.addAll(down);
		down.clear();
		for (Step s : steps) {
			if (time >= s.at() && time < s.at() + s.hold()) {
				down.add(s.button());
			}
		}
		lx = ly = rx = ry = 0;
		for (Stick s : sticks) {
			if (time >= s.at() && time < s.at() + s.hold()) {
				if (s.right()) {
					rx = s.x();
					ry = s.y();
				} else {
					lx = s.x();
					ly = s.y();
				}
			}
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
		return rx;
	}

	@Override
	public float rightY() {
		return ry;
	}

	public float time() {
		return time;
	}
}
