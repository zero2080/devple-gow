package devple.gow.tools.script;

import java.util.ArrayList;
import java.util.List;

/**
 * SCR_Entities 엔티티 타입별 핸들러 슬롯 표 (13 문서 §2).
 * 핸들러 ID = 슬롯 번호. 원작은 타입별 초기화 함수(vt+0x18, 0x1A24B8..0x1A45D0)가 슬롯을 선언 순서대로
 * {@code Slot_Bind}(0x1A9FD0) 로 묶고, 전역 카운터(0x3067CC)가 슬롯 번호가 된다.
 * 핸들러가 "push 상수; return; exit" 면 상수로 접어 두고(0x1A8A30), 아니면 매번 VM 으로 평가한다.
 * <pre>
 * 값 타입: 0 float, 1 int, 2 bool, 3 string, 4 action(코드 실행만)
 * </pre>
 */
public final class EntitySlots {

	public static final int FLOAT = 0, INT = 1, BOOL = 2, STRING = 3, ACTION = 4;

	/** 슬롯: 이름, 값 타입, 기본값(핸들러가 없을 때). */
	public record Slot(String name, int type, Object def) {
	}

	/** 공통 블록 (센서·이펙터 7 슬롯) 의 이름. 타입별 시작 번호는 {@link #baseIndex(int)}. */
	public static final String CONDITION = "Condition", REFRACT = "RefractTime", DELAY = "Delay",
			DELAYED_ENABLE = "DelayedEnable", ACTION_SLOT = "Action", DEBUG = "DebugMsg", FILTER = "Filter";

	private static final List<List<Slot>> TABLE = new ArrayList<>();
	/** 연쇄 호출용 더미. */
	private static final EntitySlots B = new EntitySlots();

	private EntitySlots() {
	}

	static {
		for (int t = 0; t < 16; t++) {
			TABLE.add(new ArrayList<>());
		}
		for (int t = 0; t <= 3; t++) {
			base(t);
		}
		add(4, "EventId", INT, 0).add(4, "unk1", INT, 0).add(4, "PromptOnEnter", INT, 0);
		base(4);
		add(5, "AnimName", STRING, "").add(5, "unk1", INT, 0).add(5, "unk2", FLOAT, 1f).add(5, "unk3", FLOAT, 0f); // 1 모드 비트, 2 속도 (기본 1.0, 0x1A2D50), 3 시작 시간 (Platforms.play)
		base(5);
		add(6, "AnimName", STRING, "").add(6, "unk1", INT, 0);
		for (int i = 2; i <= 5; i++) {
			add(6, "unk" + i, FLOAT, 0f);
		}
		add(6, "unk6", INT, 0);
		base(6);
		String[] vis = {"Visible", "Attr1E", "Attr27", "Attr11_0", "Attr11_1", "Attr11_3", "Attr11_2", "Attr1A"};
		for (String n : vis) {
			add(7, n, INT, 0);
		}
		add(7, "FadeTime", FLOAT, 0f);
		base(7);
		add(7, "Attr17", INT, 0).add(7, "JointVisibility", INT, 0);
		add(8, "EventId", INT, 0).add(8, "Arg1", INT, 0).add(8, "Arg2", INT, 0);
		base(8);
		add(9, "PlayerName", STRING, "").add(9, FILTER, INT, 0).add(9, "CameraName", STRING, "");
		add(10, "MaximumCount", INT, 0).add(10, "unk1", INT, -1).add(10, "SpawnAtStart", BOOL, false).add(10, "Respawn", BOOL, false)
				.add(10, "TemplateId", INT, 0).add(10, "unk5", INT, 0).add(10, "unk6", INT, 0).add(10, "SpawnAction", INT, -1)
				.add(10, "InitState", INT, 0).add(10, "Patrol", STRING, "");
		// 7 SpawnAction: 생성 직후 요청할 적 CombatFile 액션 ID (기본 -1 = 없음; 500 SPAWN, 504 SKSWALLSPAWN, 523 사다리, 0..2 도발) —
		//   0x1AC850 → 생성 데이터 +6 → Enemy1 초기화 0x22CB18 (AI +0x94). 8 InitState: 5 면 석화 메시지 0x407 (0x22CBF8) [확실: 코드 경로, 32 문서]
		base(10);
		add(11, "Name0", STRING, "").add(11, "Name1", STRING, "").add(11, "unk2", FLOAT, 0f);
		for (int i = 3; i <= 5; i++) {
			add(11, "unk" + i, INT, -1);
		}
		add(11, "unk6", INT, 0).add(11, "unk7", INT, 0).add(11, "CreateAtStart", BOOL, false).add(11, "unk9", BOOL, false);
		base(11);
		add(11, "unk17", BOOL, false);
		for (int t = 12; t <= 13; t++) {
			add(t, "Priority", INT, 0);
			for (int i = 1; i <= 16; i++) {
				add(t, "Init" + i, ACTION, null);
			}
		}
		add(14, "Id", INT, 0);
		add(15, "Op", INT, 1);
		for (int i = 1; i <= 4; i++) {
			add(15, "unk" + i, FLOAT, 0f);
		}
		add(15, "unk5", INT, 0);
		base(15);
	}

	private static EntitySlots add(int type, String name, int vt, Object def) {
		TABLE.get(type).add(new Slot(name, vt, def));
		return B;
	}

	private static void base(int t) {
		add(t, CONDITION, BOOL, true).add(t, REFRACT, FLOAT, 0f).add(t, DELAY, FLOAT, 0f).add(t, DELAYED_ENABLE, BOOL, true)
				.add(t, ACTION_SLOT, ACTION, null).add(t, DEBUG, STRING, "").add(t, FILTER, INT, 0);
	}

	/** 타입의 슬롯 목록 (인덱스 = 핸들러 ID). */
	public static List<Slot> of(int type) {
		return type >= 0 && type < TABLE.size() ? TABLE.get(type) : List.of();
	}

	/** 슬롯 이름 → 번호 (-1 없음). */
	public static int index(int type, String name) {
		List<Slot> l = of(type);
		for (int i = 0; i < l.size(); i++) {
			if (l.get(i).name().equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/** 공통 블록 시작 번호 (-1 = 공통 블록 없음: Start, Data, Marker). */
	public static int baseIndex(int type) {
		return index(type, CONDITION);
	}

	public static String name(int type, int slot) {
		List<Slot> l = of(type);
		return slot >= 0 && slot < l.size() ? l.get(slot).name() : "slot" + slot;
	}
}
