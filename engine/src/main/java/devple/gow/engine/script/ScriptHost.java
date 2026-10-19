package devple.gow.engine.script;

import devple.gow.engine.game.GameFlow;
import devple.gow.engine.game.PlayerState;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * {@link ScriptWorld} 용 호스트: Entity 스코프 함수(원작 0x1AF848) 와 Internal(GameMgr 0x1B01E8) 전 함수의 인자 수.
 * 인자 수는 원작 함수가 VM 스택 깊이(+0x140)를 줄이는 양으로 확인하고, 전 레벨 핸들러 스택 균형으로 검증했다 (13 문서 §4).
 * flow / player 가 null 이면 GameMgr 메시지는 리스너로만 보낸다 (헤드리스 테스트).
 */
public class ScriptHost extends GameScriptHost {

	/** Entity 스코프 인자 수 (0x2B5BC0 점프 표, 17개). */
	public static final int[] ENTITY_ARITY = {2, 2, 3, 2, 1, 4, 1, 1, 5, 1, 1, 1, 1, 1, 2, 0, 2};
	public static final String[] ENTITY_NAMES = {"Min", "Max", "Clamp", "RandomRange", "Sqrt", "FadeOut", "FadeIn",
			"FindObject", "Printf", "EventArg", "EventArg", "EventArg", "EventArg", "PlayMovie", "MovieCtl?", "MovieSkip?",
			"PreloadMovie"};

	/** Internal 인자 수 (0x2B5E20 점프 표, 0x3B 개; 표 밖/미구현 case = 0). */
	public static final int[] INTERNAL_ARITY = new int[0x3B];

	static {
		int[][] a = {{2, 2}, {3, 1}, {4, 1}, {6, 1}, {7, 1}, {8, 1}, {9, 1}, {0xA, 2}, {0xB, 1}, {0xC, 1}, {0xF, 1}, {0x10, 1},
				{0x11, 1}, {0x12, 1}, {0x14, 1}, {0x15, 1}, {0x16, 1}, {0x17, 1}, {0x18, 1}, {0x19, 1}, {0x1A, 1}, {0x1B, 2},
				{0x1C, 1}, {0x1D, 3}, {0x1E, 1}, {0x1F, 3}, {0x2E, 1}, {0x2F, 1}, {0x31, 2}, {0x33, 2}, {0x34, 4}, {0x35, 4},
				{0x36, 2}, {0x37, 5}, {0x38, 2}, {0x3A, 1}};
		for (int[] p : a) {
			INTERNAL_ARITY[p[0]] = p[1];
		}
	}

	private final GameFlow flow;
	private final PlayerState player;
	private final Random random = new Random(1);
	/** 이미 초기화한 GlobalData 엔티티 이름 (게임 전체에서 한 번). */
	final Set<String> initedGlobals;
	ScriptListener listener = new ScriptListener() {
	};
	ScriptWorld world;
	/** 실행 중 엔티티 (원작 0x29D928). */
	ScriptEntity current;

	public ScriptHost(GameFlow flow, PlayerState player) {
		super(flow, player);
		this.flow = flow;
		this.player = player;
		this.initedGlobals = new HashSet<>();
	}

	private ScriptHost(ScriptHost main) {
		super(main.flow, main.player, main.globalData());
		this.flow = main.flow;
		this.player = main.player;
		this.initedGlobals = main.initedGlobals;
	}

	/**
	 * 이웃 레벨 스크립트용 호스트: GlobalData·초기화 기록은 공유, LevelData 는 따로 (원작은 레벨 슬롯마다 LevelData 엔티티).
	 */
	public static ScriptHost neighbour(ScriptHost main) {
		return new ScriptHost(main);
	}

	public ScriptHost() {
		this(null, null);
	}

	public static int arity(int scope, int func) {
		if (scope == 0) {
			return func < ENTITY_ARITY.length ? ENTITY_ARITY[func] : 0;
		}
		return scope == 1 && func < INTERNAL_ARITY.length ? INTERNAL_ARITY[func] : 0;
	}

	/** 스택 위 n 개를 push 순서로 (꺼내지 않음). */
	private static List<Object> peek(Deque<Object> st, int n) {
		List<Object> out = new ArrayList<>();
		Iterator<Object> it = st.iterator();
		for (int i = 0; i < n && it.hasNext(); i++) {
			out.addFirst(it.next());
		}
		return out;
	}

	private static List<Object> pop(Deque<Object> st, int n) {
		List<Object> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.addFirst(st.pop());
		}
		return out;
	}

	private static String s(Object o) {
		return String.valueOf(o);
	}

	@Override
	public Object call(EscContext ctx, int scope, int func, int rt, Deque<Object> st) {
		if (scope != 1) {
			return entityCall(ctx, func, rt, st);
		}
		int n = arity(1, func);
		List<Object> args = peek(st, n);
		boolean useFlow = flow != null && func >= LOAD && func <= LOAD_CHECK;
		switch (func) {
			case CHECKPOINT -> listener.checkpoint(current);
			case LOAD -> listener.level(new ScriptListener.LevelRequest(ScriptListener.LevelOp.LOAD, s(args.get(0)).toUpperCase(Locale.ROOT), s(args.get(1)), null));
			case LOAD_WAD -> listener.level(new ScriptListener.LevelRequest(ScriptListener.LevelOp.LOAD_WAD, s(args.get(0)).toUpperCase(Locale.ROOT), null, null));
			case LOAD_FOR_WARP -> listener.level(new ScriptListener.LevelRequest(ScriptListener.LevelOp.LOAD_FOR_WARP, s(args.get(0)).toUpperCase(Locale.ROOT), null, null));
			case WARP -> listener.level(new ScriptListener.LevelRequest(ScriptListener.LevelOp.WARP, null, null, null));
			case LOAD_CHECK -> {
				String wad = s(args.get(0)).toUpperCase(Locale.ROOT);
				listener.level(new ScriptListener.LevelRequest(ScriptListener.LevelOp.LOAD_CHECK, wad, null, null));
				if (!useFlow) {
					st.pop();
					return listener.loadCheck(wad);
				}
			}
			case GOTO -> {
				st.pop();
				if (world != null) {
					world.gotoEntity(s(args.get(0)));
				}
				return null;
			}
			case 0x8 -> listener.camera(s(args.get(0)));
			case SHOW_MESSAGE -> listener.message(EscVm.i(args.get(0)), EscVm.f(args.get(1)));
			case SHOW_ITEM_CARD -> listener.message(EscVm.i(args.get(0)), EscVm.f(args.get(1)));
			case 0x1B, 0x1C, 0x1D, 0x38 -> listener.music(switch (func) {
				case 0x1B -> "MusicGroupStart";
				case 0x1C -> "MusicGroupStop";
				case 0x1D -> "MusicGroupParam";
				default -> "PlaySound";
			}, args);
			default -> listener.internal(func, args);
		}
		if (useFlow || func == GET_PLAY_TIME || (func == SHOW_ITEM_CARD && player != null)) {
			return super.call(ctx, scope, func, rt, st);
		}
		pop(st, n);
		return null;
	}

	/** Entity 스코프 (원작 0x1AF848). */
	@Override
	protected Object entityCall(EscContext ctx, int func, int rt, Deque<Object> st) {
		List<Object> a = pop(st, arity(0, func));
		return switch (func) {
			case 0 -> Math.min(EscVm.f(a.get(0)), EscVm.f(a.get(1)));
			case 1 -> Math.max(EscVm.f(a.get(0)), EscVm.f(a.get(1)));
			case 2 -> Math.min(Math.max(EscVm.f(a.get(0)), EscVm.f(a.get(1))), EscVm.f(a.get(2)));
			case 3 -> EscVm.f(a.get(0)) + random.nextFloat() * (EscVm.f(a.get(1)) - EscVm.f(a.get(0)));
			case 4 -> (float) Math.sqrt(EscVm.f(a.get(0)));
			case 8 -> {
				listener.debug(current, String.valueOf(a.get(0)));
				yield null;
			}
			case 9, 10, 11, 12 -> {
				int i = EscVm.i(a.get(0));
				Object v = current != null && i >= 0 && i < 3 ? current.eventArgs[i] : 0;
				yield switch (rt) {
					case 0 -> EscVm.f(v);
					case 1 -> v instanceof String ? 0 : EscVm.i(v);
					case 2 -> EscVm.bool(v);
					default -> String.valueOf(v);
				};
			}
			case 13 -> {
				listener.movie(String.valueOf(a.get(0)), 0, 0);
				yield null;
			}
			case 14 -> {
				listener.movie(String.valueOf(a.get(0)), 1, EscVm.i(a.get(1)));
				yield null;
			}
			case 16 -> {
				listener.movie(String.valueOf(a.get(0)), 2, EscVm.i(a.get(1)));
				yield null;
			}
			default -> null;
		};
	}
}
