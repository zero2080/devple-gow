package devple.gow.engine.boss;

import devple.gow.engine.combat.RepeatPressQte;
import devple.gow.engine.combat.TimedPressQte;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;

import java.util.EnumSet;
import java.util.Set;

/**
 * 미니게임 자동 입력 (보스 데모 --boss-demo / 헤드리스 검증). 타이밍 프롬프트 = 그 버튼 / 스틱 회전 = 사분면 순서 4틱마다,
 * 연타 = 0.1초마다 그 버튼. 그 밖에는 아무것도 누르지 않는다.
 */
public final class AutoPad extends Input {

	private final Set<PadButton> down = EnumSet.noneOf(PadButton.class), prev = EnumSet.noneOf(PadButton.class);
	private float lx, ly;
	private Object prompt;
	private int step, frame;

	public AutoPad() {
		super(0);
	}

	/** 틱마다 현재 미니게임 상태로 입력을 정한다. */
	public void drive(TimedPressQte timed, RepeatPressQte qte) {
		prev.clear();
		prev.addAll(down);
		down.clear();
		frame++;
		lx = ly = 0;
		if (timed != null) {
			if (timed != prompt) {
				prompt = timed;
				step = 0;
			}
			if (timed.button() != null) {
				down.add(timed.button());
			} else {
				int[] seq = timed.sequence();
				int q = seq[Math.min(seq.length - 1, step++ / 4)];
				lx = q == 2 ? 1 : q == 4 ? -1 : 0;
				ly = q == 1 ? 1 : q == 3 ? -1 : 0;
			}
		} else if (qte != null && qte.state() == RepeatPressQte.State.RUNNING && frame % 6 == 0) {
			down.add(qte.button());
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
}
