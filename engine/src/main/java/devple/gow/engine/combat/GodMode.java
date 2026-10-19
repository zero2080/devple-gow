package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;

/**
 * 신들의 분노 (10 문서 §9). 게이지는 {@link Combatant#godMeter} (이벤트 21 GOD_METER_ADD 로 충전).
 * 수치는 TWK {@code /Player/}: God Mode On/Off/End Threshold, {@code Weapon Level n/God Mode Meter Drain | Allow God Mode}.
 */
public final class GodMode {

	private final Tweaks tw;
	private boolean active;

	public GodMode(Tweaks tw) {
		this.tw = tw;
	}

	public boolean active() {
		return active;
	}

	/** 게이지가 준비됐는지 (On Threshold 99). */
	public boolean ready(Combatant c) {
		return c.godMeter >= tw.f("/Player/", "God Mode On Threshold", 99f);
	}

	private boolean allowed(int bladeLevel) {
		// bool 형: 하위 바이트만 유효 (lv0 = 0)
		return (tw.i("/Player/Weapon Level " + bladeLevel + "/", "Allow God Mode", 0) & 0xFF) != 0;
	}

	/**
	 * @param activatePressed L3+R3 동시 (둘 다 홀드 중 하나를 새로 누름)
	 * @return 이번 틱에 발동했으면 true
	 */
	public boolean update(Combatant c, boolean activatePressed, int bladeLevel, float hp, float dt) {
		if (active) {
			c.godMeter -= tw.f("/Player/Weapon Level " + bladeLevel + "/", "God Mode Meter Drain", 7.0715f) * dt;
			if (c.godMeter <= tw.f("/Player/", "God Mode Off Threshold", 1f)) {
				c.godMeter = Math.max(0, c.godMeter);
				active = false;
			}
			return false;
		}
		if (activatePressed && hp > 0 && allowed(bladeLevel) && ready(c)) {
			active = true;
			return true;
		}
		return false;
	}

	/** 남은 시간이 End Threshold(3초) 미만 → 종료 사운드 시점. */
	public boolean ending(Combatant c, int bladeLevel) {
		float drain = tw.f("/Player/Weapon Level " + bladeLevel + "/", "God Mode Meter Drain", 7.0715f);
		return active && drain > 0 && c.godMeter / drain < tw.f("/Player/", "God Mode End Threshold", 3f);
	}
}
