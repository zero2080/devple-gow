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

	private final List<Step> steps;
	private final Set<PadButton> down = EnumSet.noneOf(PadButton.class);
	private final Set<PadButton> prev = EnumSet.noneOf(PadButton.class);
	private float time;

	public ScriptedInput(long window, List<Step> steps) {
		super(window);
		this.steps = steps;
	}

	public void advance(float dt) {
		time += dt;
		prev.clear();
		prev.addAll(down);
		down.clear();
		for (Step s : steps) {
			if (time >= s.at() && time < s.at() + s.hold()) {
				down.add(s.button());
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
		return 0;
	}

	@Override
	public float leftY() {
		return 0;
	}

	@Override
	public float rightX() {
		return 0;
	}

	@Override
	public float rightY() {
		return 0;
	}

	public float time() {
		return time;
	}
}
