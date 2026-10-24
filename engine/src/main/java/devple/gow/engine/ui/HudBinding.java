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
	/** 미터 자동 표시 ({@link #meter}, 게임 씬). false 면 첫 갱신에 MainMeter 를 띄우고 그대로 둔다 (UI·영웅 시험 씬). */
	public boolean autoMeter;
	/** 미터가 마지막으로 본 값. 게임 씬은 세션 것을 넣어 레벨을 넘어 유지한다 (원작 정적 변수). */
	public MeterSeen seen = new MeterSeen();
	/** MainMeter 가 올라와 있는가 (원작 HUD +0x157C) 와 남은 유지 시간 (Progress +0x1C4). */
	private boolean meterShown;
	private float meterHold;

	/**
	 * 미터가 마지막으로 본 값 (원작 정적 0x29DC34 선택 마법 · 0x29DC38 HP · 0x29DC3C 최대 HP · 0x29DC40 MP · 0x29DC44 최대 MP ·
	 * 0x29DC48 붉은 오브, 부팅 시 0) + 처리한 표시 요청 수 ({@link PlayerState#meterPokes}).
	 */
	public static final class MeterSeen {
		int magic, orbs, pokes;
		float hp, maxHp, mp, maxMp;
	}

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

	/** 매 프레임: 바뀐 값만 이벤트로 알린다. 자동 표시가 아니면 첫 호출이 MainMeter 를 띄운다. */
	public void update(PlayerState ps, UiVariables v) {
		boolean first = Float.isNaN(lastHp);
		write(ps, v);
		if (first && !autoMeter) {
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

	/**
	 * 미터 자동 표시 (원작 Progress_UpdateHud 0x1D7148 [확실: 디스어셈블]). 매 프레임 (전역 정지가 아닐 때):
	 * <ul>
	 *   <li>영웅이 전투 기술 중 (combatant vt+0x64 → +0x4C ≠ 0), 마법 재생 중 (0x3044A4 ≠ 0), 표시 요청 (+0x1C0), 또는 선택 마법 ·
	 *       HP · 최대 HP · MP · 최대 MP · 붉은 오브 가 마지막으로 본 값과 다르면: 값을 기록하고 유지 시간 = Meter Bar Hold Time (0x1D72DC),
	 *       미터가 내려가 있으면 MeterBar 1 (0x1D7378 → 0x1DCC00, +0xC 비트 8 은 [미확인] 이라 뺐다)</li>
	 *   <li>아니면 미터가 올라와 있을 때만 유지 시간을 프레임마다 1/60 씩 (시간 배율 없이) 줄여 0 이하에서 MeterBar 0 (0x1D7340)</li>
	 * </ul>
	 * HP 가 1 미만이면 HUD 리셋 (0x1D71CC → 0x1BA150) 처럼 미터를 내린다. 게임 씬은 진행 중 탐험에서 미터가 사라지고 싸우거나 오브를 먹으면
	 * 다시 뜬다 (PS2 영상 5664 초 없음 → 5665 초 공격 → 5670 초 사라짐).
	 *
	 * @param busy 영웅이 전투 기술 중 (Combatant.active)
	 * @param real 프레임 시간 (원작은 고정 1/60)
	 */
	public void meter(PlayerState ps, UiVariables v, boolean busy, float real) {
		if (ps.hp < 1f) {
			hideMeter(v);
			return;
		}
		int magic = ps.selectedMagic != null ? ps.selectedMagic.itemId : 0;
		MeterSeen s = seen;
		if (busy || ps.mpRegen != 0 || ps.meterPokes != s.pokes || magic != s.magic || ps.hp != s.hp || ps.maxHp() != s.maxHp
				|| ps.mp != s.mp || ps.maxMp() != s.maxMp || ps.redOrbs != s.orbs) {
			s.magic = magic;
			s.hp = ps.hp;
			s.maxHp = ps.maxHp();
			s.mp = ps.mp;
			s.maxMp = ps.maxMp();
			s.orbs = ps.redOrbs;
			s.pokes = ps.meterPokes;
			meterHold = ps.meterHoldTime();
			if (!meterShown) {
				meterShown = true;
				v.event("PS2_MeterBar_Event", 1);
			}
		} else if (!meterShown) {
			meterHold = 0;
		} else if (meterHold > 0) {
			meterHold -= real;
			if (meterHold <= 0) {
				hideMeter(v);
			}
		}
	}

	/** 미터를 내린다 (HUD 리셋 0x1BA150: 올라와 있을 때만 MeterBar 0). 다시 뜨는 것은 {@link #meter} 의 변화뿐. */
	public void hideMeter(UiVariables v) {
		if (meterShown) {
			meterShown = false;
			v.event("PS2_MeterBar_Event", 0);
		}
	}

	public boolean meterShown() {
		return meterShown;
	}
}
