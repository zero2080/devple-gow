package devple.gow.engine.ui;

import devple.gow.engine.game.PlayerState;
import devple.gow.engine.game.Weapon;

/**
 * PlayerState → FLP_HUDK 변수 (원작 Progress_UpdateHud 0x1D7148 / Progress_ToHud 0x1D7678 대응, 11 문서 §1.2).
 * <p>
 * 미터 갱신 규약 (HUD 루트 SimKeyEvent 스크립트):
 * <ul>
 *   <li>{@code PS2_MeterBar_Event = 1} → MainMeter AnimateOn + SetHealthMeter/SetMagicMeter 호출, 0 → AnimateOff</li>
 *   <li>{@code PS2_HealthMeter_Event / PS2_MagicMeter_Event = 1} → 값 반영 (HealthMeter 클립 프레임 = 1 + 값/2, 값 0..200)</li>
 *   <li>{@code PS2_PowerOrb_Event = 1} → 오브 미터 (표시 = count mod Capacity, 배수 = count div Capacity)</li>
 *   <li>{@code PS2_PauseMenu_Event = 1} 일시정지 열기 / 0 닫기, {@code PS2_PSMMenu_Event = 1} 업그레이드 메뉴</li>
 * </ul>
 */
public final class HudBinding {

	/** 원작 PowerOrb_Capacity 상수 (실행 파일 0x1B6B58). */
	public static final int POWER_ORB_CAPACITY = 300;

	private float lastHp = Float.NaN, lastMp = Float.NaN;
	private int lastOrbs = -1, lastHpLevel = -1, lastMpLevel = -1;
	/** SetHealth/MagicMeter 는 저장된 단계(_level0/:X_Level)가 현재 단계와 같을 때만 바를 갱신하므로 단계 변경 후 한 번 더 보낸다. */
	private int resync;
	private int lastPickUp = Integer.MIN_VALUE;
	/** 선택 마법 시전 가능 여부 (원작 sub_001D93C0 HasMagic). 게임 씬이 Magic::has 를 연결. */
	public java.util.function.IntPredicate castable = id -> true;

	/**
	 * 선택 마법 아이콘 값 (Progress_UpdateHud 0x1D75E8): 0 = 없음, 시전 가능 2·id − 1, 불가 2·id [추정: 짝/홀 의미].
	 * FLP_HUDK 는 {@code PS2_PickUp_Event} 로 MainMeter/PickUps 를 GotoFrame(1 + 값).
	 */
	public static int pickUpValue(PlayerState ps, boolean castable) {
		if (ps.selectedMagic == null || ps.selectedMagic.itemId == 0) {
			return 0;
		}
		int id = ps.selectedMagic.itemId;
		return castable ? 2 * id - 1 : 2 * id;
	}

	/**
	 * 값만 기록 (이벤트 없이). 메뉴 진입 직전 (Progress_ToHud) 에도 쓴다.
	 * 미터 값은 HP/MP 원값 (최대 200): HealthMeter 클립 프레임 = 1 + 값/2.
	 * 최대치 단계는 검은 바 라벨 BarLevel&lt;n&gt; — 체력은 hpLevel(0 = 라벨 없는 frame 0 = 최대 100),
	 * 마법은 mpLevel (0 = 마법 없음, 스크립트가 BarLevel(n−1) 로 이동; BarLevel0 = 최대 100).
	 */
	public static void write(PlayerState ps, UiVariables v) {
		v.set("PS2_HealthMeter_Value", Math.round(Math.max(0, ps.hp)));
		v.set("PS2_MagicMeter_Value", Math.round(Math.max(0, ps.mp)));
		v.set("PS2_HealthMeter_Level", ps.hpLevel);
		v.set("PS2_MagicMeter_Level", ps.mpLevel);
		v.set("PS2_PowerOrb_Count", ps.redOrbs);
		v.set("PS2_PowerOrb_Capacity", POWER_ORB_CAPACITY);
		v.set("PS2_HealthShard_Count", ps.gorgonEyes);
		// 엔진이 메뉴 입력을 허용하는지 (메뉴 ○/△ 핸들러의 첫 조건). 원작 엔진이 1 로 둔다
		if (!v.has("PS2_EnableButtons") || v.get("PS2_EnableButtons").isEmpty()) {
			v.set("PS2_EnableButtons", 1);
		}
		// 첫 파워업 안내 여부 (원작 PlayerProgress 첫 사용 힌트 비트). 0 이어야 메뉴 닫기(Animate_Off) 조건이 통과
		if (!v.has("PS2_PowerUp_FirstTime") || v.get("PS2_PowerUp_FirstTime").isEmpty()) {
			v.set("PS2_PowerUp_FirstTime", 0);
		}
		v.set("PS2_MagicShard_Count", ps.phoenixFeathers);
		for (Weapon w : Weapon.values()) {
			v.set("PS2_" + w.key + "_Level", ps.level.get(w));
			v.set("PS2_" + w.key + "Orb_Count", ps.invested.get(w));
			v.set("PS2_" + w.key + "_Status", ps.owned(w) ? 1 : 0);
		}
	}

	/** 매 프레임: 바뀐 값만 이벤트로 알린다. 첫 호출은 MainMeter 를 띄운다. */
	public void update(PlayerState ps, UiVariables v) {
		boolean first = Float.isNaN(lastHp);
		write(ps, v);
		if (first) {
			v.event("PS2_MeterBar_Event", 1);
		}
		boolean again = resync > 0;
		if (again) {
			resync--;
		}
		if (ps.hpLevel != lastHpLevel || ps.mpLevel != lastMpLevel) {
			resync = 1;
		}
		if (again || ps.hp != lastHp || ps.hpLevel != lastHpLevel) {
			v.event("PS2_HealthMeter_Event", 1);
		}
		if (again || ps.mp != lastMp || ps.mpLevel != lastMpLevel) {
			v.event("PS2_MagicMeter_Event", 2);
		}
		int pu = pickUpValue(ps, ps.selectedMagic != null && castable.test(ps.selectedMagic.itemId));
		if (pu != lastPickUp) {
			lastPickUp = pu;
			v.event("PS2_PickUp_Event", pu);
		}
		if (ps.redOrbs != lastOrbs) {
			v.event("PS2_PowerOrb_Event", 1);
		}
		lastHp = ps.hp;
		lastMp = ps.mp;
		lastOrbs = ps.redOrbs;
		lastHpLevel = ps.hpLevel;
		lastMpLevel = ps.mpLevel;
	}
}
