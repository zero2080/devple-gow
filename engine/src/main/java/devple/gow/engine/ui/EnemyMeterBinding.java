package devple.gow.engine.ui;

import devple.gow.engine.combat.EnemyFighter;

import java.util.Map;

/**
 * 보스 체력 미터 (레벨 WAD 의 FLP_&lt;레벨&gt; — ATHN01B/C/E, ATHN08A/B/C, PAND03G — SimKeyEvent, 37 문서 §HUD).
 * <pre>
 * PS2_EnemyMeter_Event{n}: 1 = 미터 켬 (HydraMeter{n} On), 0 = 끔, 그 밖(2) = 값 갱신 → /:EnemySetMeter{n}
 * PS2_EnemyMeter_Value{n}: HydraMeterMain 프레임 = 2 + 값 (클립 102 프레임 → 값 0..99) [추정: 체력 % 로 사용]
 * PS2_EnemyState_Event{n} / _Value{n}: 0 보통, 1 회복 반짝임(EnemyEyeGlow/MeterSparkleRegen On), 2 BarFlash(On1) [추정: 잡기 가능]
 * </pre>
 * 슬롯 n 은 스크립트 1055(HealthMeterOn) 순서 ({@code CombatScriptBridge.meterTargets}).
 */
public final class EnemyMeterBinding {

	private final int[] value = {-1, -1, -1, -1};
	private final int[] state = {-1, -1, -1, -1};
	private final boolean[] on = new boolean[4];

	public void update(UiVariables v, Map<Integer, EnemyFighter> targets) {
		for (int n = 1; n <= 3; n++) {
			EnemyFighter f = targets.get(n);
			boolean show = f != null && !f.dead();
			if (show != on[n]) {
				on[n] = show;
				value[n] = state[n] = -1;
				v.event("PS2_EnemyMeter_Event" + n, show ? 1 : 0);
				continue; // 켜는 프레임에는 On 애니만 (값은 다음 프레임)
			}
			if (!show) {
				continue;
			}
			int val = Math.max(0, Math.min(99, Math.round(f.hp / Math.max(1f, f.maxHp()) * 99)));
			if (val != value[n]) {
				value[n] = val;
				v.set("PS2_EnemyMeter_Value" + n, val);
				v.event("PS2_EnemyMeter_Event" + n, 2);
			}
			int st = f.grabbable() ? 2 : 0;
			if (st != state[n]) {
				state[n] = st;
				v.set("PS2_EnemyState_Value" + n, st);
				v.event("PS2_EnemyState_Event" + n, st == 0 ? 0 : 1); // 0 = 보통으로 (Wait), 양수 = Value 적용
			}
		}
	}
}
