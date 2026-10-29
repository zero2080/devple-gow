package devple.gow.engine.script;

import devple.gow.engine.game.GameFlow;
import devple.gow.engine.game.PlayerState;

import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ESC 스크립트 ↔ 게임 연결. Internal 스코프 함수 = 원작 GameMgr 메시지 번호 (11 문서 §6.2).
 * GlobalData/LevelData 변수는 여기서 보관 (세이브 대상).
 */
public class GameScriptHost implements EscHost {

	public static final int CHECKPOINT = 0, GET_PLAY_TIME = 1, LOAD = 2, LOAD_WAD = 3, LOAD_FOR_WARP = 4, WARP = 5,
			LOAD_CHECK = 6, GOTO = 7, SHOW_MESSAGE = 0xA, IDLE = 0xC, ZONE_REPORT = 0x10, MAGIC_REGEN = 0x12, SHOW_ITEM_CARD = 0x1F,
			COMPLETE_GAME = 0x2F, SET_END_GAME = 0x3A;

	private final GameFlow flow;
	private final PlayerState player;
	private final Map<Integer, Object> globalData;
	private final Map<Integer, Object> levelData = new HashMap<>();
	/** GameMgr 타이머 표 (원작 0x3368F8 하나 — 두 슬롯 레벨이 같이 쓴다, {@link ScriptTimers}). */
	public final ScriptTimers timers;
	private BiConsumer<Integer, Float> onMessage = (id, sec) -> { };
	private Consumer<String> onGoto = name -> { };
	private Runnable onCheckpoint = () -> { };
	private float playTime;

	public GameScriptHost(GameFlow flow, PlayerState player) {
		this(flow, player, new HashMap<>(), new ScriptTimers());
	}

	/** GlobalData·타이머 표를 다른 호스트와 공유 (같이 상주하는 이웃 레벨의 스크립트, 38 문서 §4). */
	protected GameScriptHost(GameFlow flow, PlayerState player, Map<Integer, Object> sharedGlobals, ScriptTimers sharedTimers) {
		this.flow = flow;
		this.player = player;
		this.globalData = sharedGlobals;
		this.timers = sharedTimers;
		this.players = () -> player;
	}

	/** Internal 플레이어 변수 (33..45) 의 대상 — 게임은 세션 플레이어를 넣는다 (호스트가 플레이어보다 먼저 생김). null = 없음 (헤드리스). */
	public java.util.function.Supplier<PlayerState> players;

	public void onMessage(BiConsumer<Integer, Float> l) {
		onMessage = l;
	}

	public void onGoto(Consumer<String> l) {
		onGoto = l;
	}

	public void onCheckpoint(Runnable l) {
		onCheckpoint = l;
	}

	public void addPlayTime(float dt) {
		playTime += dt;
	}

	/** 레벨이 바뀌면 LevelData 초기화. */
	public void resetLevelData() {
		levelData.clear();
	}

	@Override
	public Object call(EscContext ctx, int scope, int func, int rt, Deque<Object> st) {
		if (scope != 1) {
			return entityCall(ctx, func, rt, st);
		}
		switch (func) {
			case CHECKPOINT -> onCheckpoint.run();
			case GET_PLAY_TIME -> {
				return playTime;
			}
			case LOAD -> {
				st.pop(); // entry 이름 (위치는 레벨 로더가 사용)
				flow.msgLoad(String.valueOf(st.pop()));
			}
			case LOAD_WAD, LOAD_FOR_WARP -> flow.msgLoadWad(String.valueOf(st.pop()));
			case WARP -> flow.msgWarp();
			case LOAD_CHECK -> {
				return flow.msgLoadCheck(String.valueOf(st.pop()));
			}
			case GOTO -> onGoto.accept(String.valueOf(st.pop()));
			case SHOW_MESSAGE -> {
				float sec = EscVm.f(st.pop());
				onMessage.accept(EscVm.i(st.pop()), sec);
			}
			case IDLE, ZONE_REPORT, SET_END_GAME, COMPLETE_GAME -> st.pop();
			case MAGIC_REGEN -> {
				// GameMgr_OnMessage 0x12 (0x1B0B34): float 인자 → 0x3044A4 = 마법 재생 MP/초 (PlayerState.mpRegen) [확실: 코드]
				float rate = EscVm.f(st.pop());
				PlayerState p = players.get();
				if (p != null) {
					p.mpRegen = rate;
				}
			}
			case SHOW_ITEM_CARD -> {
				int kind = EscVm.i(st.pop());
				st.pop();
				int msg = EscVm.i(st.pop());
				onMessage.accept(msg, 3f);
				player.itemCard(kind);
			}
			default -> {
				// 미확인 함수: 인자 수를 모르므로 스택을 건드리지 않는다 (핸들러 종료 시 스택은 버려짐)
			}
		}
		return null;
	}

	/** Entity 스코프(0) 함수: 레벨 런타임이 오버라이드. */
	protected Object entityCall(EscContext ctx, int func, int rt, Deque<Object> st) {
		return null;
	}

	/** Internal 변수 48 DifficultyLevel (getter 0x1AFE18 → 0x1AFF94 = 0x29DB54): 0 쉬움 .. 3 신. 게임 쪽이 넣는다. */
	public java.util.function.IntSupplier difficulty = () -> 1;
	public static final int VAR_DIFFICULTY = 48;

	/**
	 * Internal 플레이어 변수 (GameMgr 변수 getter 0x1AFE18 / setter 0x1AFFB0, 점프 표 0x2B5C10 / 0x2B5C80 — 번호 − 0x21) [확실: 디스어셈블]:
	 * 33 HP (+0x178, 쓰기 = Entity_SetHP 0x1E1638 자르기) / 34 최대 HP (+0x17C) / 35 MP (0x30449C, 쓰기 = 0..최대 자르기) / 36 최대 MP
	 * (0x304060 표 [MP 레벨 0x3044B7] +0x14) / 37 아이템 9 · 38..42 아이템 3..7 보유 (0x3044C2 비트 1 &lt;&lt; (아이템 − 1); 쓰기 참 = AddItem
	 * 0x1D8CF0 (아이템, 1), 거짓 = DeductItem 0x1D9168) / 44 · 45 고르곤의 눈 · 불사조 깃털 개수 (쓰기 = AddItem (값 − 지금)).
	 * 예: ATHN02D MedusaRipZone-EventCombat1 "set_bool Internal:39 true; set_float Internal:35 ← Internal:36" = 메두사의 시선 (아이템 4) 획득 +
	 * MP 가득. 예전 엔진은 Internal 변수 쓰기를 버려 머리를 뜯어도 L2 시선 마법이 생기지 않았다.
	 */
	public static final int VAR_HP = 33, VAR_MAX_HP = 34, VAR_MP = 35, VAR_MAX_MP = 36, VAR_ITEM9 = 37, VAR_ITEM3 = 38, VAR_ITEM7 = 42,
			VAR_EYES = 44, VAR_FEATHERS = 45, VAR_BLADES = 50;

	/** Internal 변수 번호 → 아이템 번호 (보유 플래그 변수 37..42), 아니면 −1. */
	static int itemVar(int id) {
		return id == VAR_ITEM9 ? 9 : id >= VAR_ITEM3 && id <= VAR_ITEM7 ? id - VAR_ITEM3 + 3 : -1;
	}

	private Object playerVar(int id) {
		PlayerState player = players.get();
		if (player == null) {
			return null;
		}
		int item = itemVar(id);
		if (item > 0) {
			return (player.itemFlags & (1 << (item - 1))) != 0;
		}
		return switch (id) {
			case VAR_HP -> player.hp;
			case VAR_MAX_HP -> player.maxHp();
			case VAR_MP -> player.mp;
			case VAR_MAX_MP -> player.maxMp();
			case VAR_EYES -> (float) player.gorgonEyes;
			case VAR_FEATHERS -> (float) player.phoenixFeathers;
			case VAR_BLADES -> (float) (player.level.get(devple.gow.engine.game.Weapon.BLADES) + 1); // 0x1AFF84: 0x3044BD + 1
			default -> null;
		};
	}

	private boolean setPlayerVar(int id, Object value) {
		PlayerState player = players.get();
		if (player == null) {
			return false;
		}
		int item = itemVar(id);
		if (item > 0) {
			if (EscVm.bool(value)) {
				player.addItem(item, 1);
			} else {
				player.itemFlags &= ~(1 << (item - 1)); // DeductItem 0x1D9168 [근사: 보유 비트만]
			}
			return true;
		}
		switch (id) {
			case VAR_HP -> player.hp = Math.max(0, Math.min(player.maxHp(), EscVm.f(value)));
			case VAR_MP -> player.mp = Math.max(0, Math.min(player.maxMp(), EscVm.f(value)));
			case VAR_EYES -> player.addItem(10, Math.max(0, Math.round(EscVm.f(value)) - player.gorgonEyes));
			case VAR_FEATHERS -> player.addItem(11, Math.max(0, Math.round(EscVm.f(value)) - player.phoenixFeathers));
			// 50 BladesLevel 쓰기 0x1B01A0: 블레이드 레벨 바이트 0x3044BD = min(값, 6) − 1 (+ HUD Blades_Level +0x200) [확실] — ATHN07B gotBlades-giveBlades
			// 의 6 = 내부 레벨 5 = 아테나의 블레이드 R_WPN&lt;코스튬&gt;_5 (업그레이드 메뉴 최대는 4) [엔진: 0 아래로는 자름]
			case VAR_BLADES -> player.level.put(devple.gow.engine.game.Weapon.BLADES, Math.max(0, Math.min(6, Math.round(EscVm.f(value))) - 1));
			default -> {
				return false;
			}
		}
		return true;
	}

	private static Object typed(Object v, int type) {
		return switch (type) {
			case 0 -> EscVm.f(v);
			case 1 -> EscVm.i(v);
			case 2 -> EscVm.bool(v);
			default -> String.valueOf(v);
		};
	}

	@Override
	public Object getVar(EscContext ctx, int scope, int id, int type) {
		if (scope == 1 && id == VAR_DIFFICULTY) {
			// OLYMP02 PlayMovie-HistoryCredits0..3: DifficultyLevel == d → 0·1·2 "Bonus01", 3 (신) "Bonus24"
			int d = difficulty.getAsInt();
			return type == 0 ? (Object) (float) d : type == 2 ? (Object) (d != 0) : (Object) d;
		}
		if (scope == 1) {
			Object pv = playerVar(id);
			if (pv != null) {
				return typed(pv, type);
			}
		}
		Map<Integer, Object> m = scope == 2 ? globalData : scope == 3 ? levelData : null;
		Object v = m == null ? null : m.get(scope == 2 ? globalKey(id) : id);
		if (v != null) {
			return v;
		}
		return switch (type) {
			case 0 -> 0f;
			case 1 -> 0;
			case 2 -> false;
			default -> "";
		};
	}

	@Override
	public void setVar(EscContext ctx, int scope, int id, int type, Object value) {
		if (scope == 1) {
			setPlayerVar(id, value);
		} else if (scope == 2) {
			globalData.put(globalKey(id), value);
		} else if (scope == 3) {
			levelData.put(id, value);
		}
	}

	/**
	 * 스크립트 GlobalData:n 의 저장 열쇠. 원작은 GlobalData 엔티티가 메시지 0 에서 변수 (이름, 타입) 를 등록할 때 (0x1AE848 → 0x1AEC88) 전역 표
	 * 0x30063C (0x1C 바이트 × 최대 128, "Too many GlobalData variables!") 에서 <b>이름</b> 으로 (strcmp 0x28A4A4) 찾거나 붙이고, 레벨 슬롯의 번호표
	 * (0x2FFE3C + 2·(슬롯 +0x10 + 번호)) 에 그 칸을 적는다 — 읽기·쓰기 (0x1AEDE8) 는 실행 중 엔티티 슬롯의 표로 찾는다 [확실]. 그래서 같은 변수의
	 * 번호가 레벨마다 다르다 (ZoneArea: ATHN03A 0 · ATHN04A 2 · ATHN02A 1, HydraDead: ATHN01A 0 · 01B 1 · 01E 2 — 출시 데이터 이름 93 개 중 48 개).
	 * 기본 = 번호 그대로 (이름을 모름), {@link ScriptHost} 가 그 레벨의 선언 이름으로 바꾼다 (43 문서 §17).
	 */
	protected int globalKey(int id) {
		return id;
	}

	/** 변수 이름 → 저장 열쇠 (이름이 없는 번호 0..0xFFF 와 겹치지 않게 비트 30). */
	public static int globalKey(String name) {
		return 0x40000000 | (name.hashCode() & 0x3FFFFFFF);
	}

	/** 이름으로 GlobalData 값 (없으면 null). */
	public Object global(String name) {
		return globalData.get(globalKey(name));
	}

	public Map<Integer, Object> globalData() {
		return globalData;
	}

	public Map<Integer, Object> levelData() {
		return levelData;
	}
}
