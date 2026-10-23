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
			LOAD_CHECK = 6, GOTO = 7, SHOW_MESSAGE = 0xA, IDLE = 0xC, ZONE_REPORT = 0x10, SHOW_ITEM_CARD = 0x1F,
			COMPLETE_GAME = 0x2F, SET_END_GAME = 0x3A;

	private final GameFlow flow;
	private final PlayerState player;
	private final Map<Integer, Object> globalData;
	private final Map<Integer, Object> levelData = new HashMap<>();
	private BiConsumer<Integer, Float> onMessage = (id, sec) -> { };
	private Consumer<String> onGoto = name -> { };
	private Runnable onCheckpoint = () -> { };
	private float playTime;

	public GameScriptHost(GameFlow flow, PlayerState player) {
		this(flow, player, new HashMap<>());
	}

	/** GlobalData 를 다른 호스트와 공유 (같이 상주하는 이웃 레벨의 스크립트, 38 문서 §4). */
	protected GameScriptHost(GameFlow flow, PlayerState player, Map<Integer, Object> sharedGlobals) {
		this.flow = flow;
		this.player = player;
		this.globalData = sharedGlobals;
	}

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

	@Override
	public Object getVar(EscContext ctx, int scope, int id, int type) {
		if (scope == 1 && id == VAR_DIFFICULTY) {
			// OLYMP02 PlayMovie-HistoryCredits0..3: DifficultyLevel == d → 0·1·2 "Bonus01", 3 (신) "Bonus24"
			int d = difficulty.getAsInt();
			return type == 0 ? (Object) (float) d : type == 2 ? (Object) (d != 0) : (Object) d;
		}
		Map<Integer, Object> m = scope == 2 ? globalData : scope == 3 ? levelData : null;
		Object v = m == null ? null : m.get(id);
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
		if (scope == 2) {
			globalData.put(id, value);
		} else if (scope == 3) {
			levelData.put(id, value);
		}
	}

	public Map<Integer, Object> globalData() {
		return globalData;
	}

	public Map<Integer, Object> levelData() {
		return levelData;
	}
}
