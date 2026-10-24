package devple.gow.engine.ui;

import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * FLP 무비 런타임 (플래시 4 플레이어 축소판). docs/21 참고.
 * <ul>
 *   <li>표시 목록: 루트/무비클립/입력 스프라이트 타임라인의 키프레임(시작 프레임 기준)을 적용해 노드를 만들고 갱신.</li>
 *   <li>프레임 스크립트: 프레임 진입 시 큐에 넣고, 현재 스크립트가 끝난 뒤 실행 (플래시 액션 큐 순서).
 *       goto 는 정지(gotoAndStop) 이며 뒤의 Play 가 재생을 켠다.</li>
 *   <li>입력: Data6 스프라이트 핸들러 (조건 5 누름 / 9 뗌 / 16 매 프레임 SimKeyEvent).
 *       스크립트 문맥은 스프라이트의 부모 (플래시 버튼 액션과 동일).</li>
 *   <li>변수: 클립별 변수 + {@code PS2_*} 는 {@link UiVariables} (엔진 공유), {@code PS2_<숫자>} 미설정 시 메시지 테이블.</li>
 * </ul>
 */
public final class FlpPlayer {

	/** FSCommand(GetURL "FSCommand:PS2_x" "arg") 수신. */
	public interface CommandListener {
		void command(String command, String arg);
	}

	final FlpFile flp;
	final UiVariables vars;
	final MessageTable messages;
	final FlpNode root;
	private final FlpVm vm = new FlpVm(this);
	private final Deque<Object[]> actions = new ArrayDeque<>();
	private final Map<FlpFile.Timeline, Map<Integer, List<byte[]>>> frameScripts = new IdentityHashMap<>();
	private final List<CommandListener> commandListeners = new ArrayList<>();
	/** UI 틱 수 (엔진 고정 60Hz 와 1:1). GetTime 은 이 값 기준 — 결정적이고 일시정지에도 일관. */
	private long tickCount;
	private int pressed, released, held;
	/** 디버그: 실행 추적. */
	public boolean trace;

	public FlpPlayer(FlpFile flp, UiVariables vars, MessageTable messages) {
		this.flp = flp;
		this.vars = vars;
		this.messages = messages;
		initEvents();
		root = new FlpNode(FlpNode.Kind.MOVIE, -1, 0, null, flp.root, null, -1);
		gotoFrame(root, 0);
		root.playing = true;
		runActions();
	}

	/** 스크립트에 나오는 PS2_*_Event 변수를 -1(이벤트 없음)로 초기화. 미정의("")는 0 으로 읽혀 이벤트로 오인된다. */
	private void initEvents() {
		String s = new String(flp.strings, java.nio.charset.StandardCharsets.ISO_8859_1);
		for (String part : s.split("\0")) {
			String n = part.startsWith("_level0/:") || part.startsWith("_Level0/:") ? part.substring(9) : part;
			if (UiVariables.isGlobal(n) && n.endsWith("_Event") && !vars.has(n)) {
				vars.set(n, "-1");
			}
		}
		if (!vars.has("PS2_EnableButtons")) {
			vars.set("PS2_EnableButtons", "1");
		}
	}

	public void addCommandListener(CommandListener l) {
		commandListeners.add(l);
	}

	public MessageTable messages() {
		return messages;
	}

	public UiVariables variables() {
		return vars;
	}

	public FlpFile file() {
		return flp;
	}

	// ---------------------------------------------------------------- 프레임 진행

	/** 한 프레임: 입력 이벤트 → SimKeyEvent → 타임라인 진행 → 프레임 스크립트. */
	public void tick() {
		tickCount++;
		List<FlpNode> sprites = new ArrayList<>();
		collect(root, sprites, true);
		for (FlpNode s : sprites) {
			for (FlpFile.InputHandler h : s.sprite.inputs()) {
				boolean fire = switch (h.unk()) {
					case 5 -> (h.keyMask() & pressed) != 0;
					case 9 -> (h.keyMask() & released) != 0;
					case 16 -> h.keyMask() == 0;
					default -> false;
				};
				if (fire && !s.removed) {
					run(h.script(), s.parent);
				}
			}
		}
		pressed = released = 0;
		runActions();

		List<FlpNode> movies = new ArrayList<>();
		collect(root, movies, false);
		for (FlpNode m : movies) {
			if (m.removed || !m.playing || m.sprite != null) {
				continue;
			}
			int total = m.totalFrames();
			if (total <= 1) {
				m.playing = false;
				continue;
			}
			showFrame(m, (m.frame + 1) % total);
		}
		runActions();
	}

	/** 패드 상태 (FLP 버튼 마스크, {@link UiPad}). 누름/뗌 에지는 다음 tick 에서 처리. */
	public void pad(int mask) {
		pressed |= mask & ~held;
		released |= held & ~mask;
		held = mask;
	}

	private void collect(FlpNode n, List<FlpNode> out, boolean spritesOnly) {
		if (n.kind != FlpNode.Kind.MOVIE) {
			return;
		}
		if (!spritesOnly || n.sprite != null) {
			out.add(n);
		}
		for (FlpNode s : n.slots) {
			if (s != null) {
				collect(s, out, spritesOnly);
			}
		}
		for (FlpNode s : n.attached) {
			collect(s, out, spritesOnly);
		}
	}

	// ---------------------------------------------------------------- 엔진 API

	/** 라벨 스크립트를 즉시 실행 (Call). 예: {@code call("/:SetHealthMeter")}. */
	public void call(String pathLabel) {
		vm.call(root, pathLabel);
		runActions();
	}

	/** 경로의 클립을 라벨로 이동 (예: {@code gotoLabel("/MainMeter", "AnimateOn", true)}). */
	public boolean gotoLabel(String path, String label, boolean play) {
		FlpNode n = resolve(root, path);
		if (n == null || !gotoLabel(n, label)) {
			return false;
		}
		n.playing = play;
		runActions();
		return true;
	}

	/**
	 * 이름 붙은 심볼을 루트에 붙인다 (원작 sub_0014A980: HUD 미니게임 아이콘 XButtonGreek 등 — 루트 타임라인 키프레임 중 그 이름의
	 * 배치를 찾아 같은 변환으로 새 인스턴스를 만든다. 루트 타임라인이 지나가도 남는다). 없으면 false.
	 */
	public boolean attach(String name) {
		if (attachedNode(name) != null) {
			return true;
		}
		List<FlpFile.Layer> layers = root.timeline.layers();
		for (FlpFile.Layer layer : layers) {
			List<FlpFile.KeyFrame> keys = layer.keys();
			for (int i = 0; i < keys.size(); i++) {
				FlpFile.KeyFrame k = keys.get(i);
				if (k.name() == null || !k.name().equalsIgnoreCase(name) || k.handler() < 0 || k.handler() >= flp.handlers.size()) {
					continue;
				}
				FlpNode n = create(root, flp.handlers.get(k.handler()), k.handler(), i);
				n.name = k.name();
				apply(n, k);
				root.attached.add(n);
				if (n.kind == FlpNode.Kind.MOVIE) {
					showFrame(n, 0);
				}
				runActions();
				return true;
			}
		}
		return false;
	}

	/** {@link #attach} 한 심볼 떼기 (원작 sub_0014AB18). */
	public void detach(String name) {
		FlpNode n = attachedNode(name);
		if (n != null) {
			n.markRemoved();
			root.attached.remove(n);
		}
	}

	public boolean attached(String name) {
		return attachedNode(name) != null;
	}

	private FlpNode attachedNode(String name) {
		for (FlpNode n : root.attached) {
			if (n.name != null && n.name.equalsIgnoreCase(name)) {
				return n;
			}
		}
		return null;
	}

	/** 스크립트 변수 읽기 ({@code "_level0/:PausePowerUp_State"}, {@code "PS2_X"}). */
	public String getVariable(String name) {
		return vm.getVar(root, name);
	}

	public void setVariable(String name, String value) {
		vm.setVar(root, name, value);
	}

	/** 클립 경로 → 현재 프레임 (없으면 -1). 검증/디버그용. */
	public int frameOf(String path) {
		FlpNode n = resolve(root, path);
		return n == null || n.kind != FlpNode.Kind.MOVIE ? -1 : n.frame;
	}

	/** 표시 트리 덤프 (디버그). */
	public void dump(Consumer<String> out) {
		dump(root, 0, out);
	}

	private void dump(FlpNode n, int depth, Consumer<String> out) {
		String extra = n.kind == FlpNode.Kind.TEXT ? " text=" + flp.dynamicLabels.get(n.index).variable() : n.kind == FlpNode.Kind.MESH ? " mesh#" + n.index : "";
		out.accept("  ".repeat(depth) + n + extra + String.format(" a=%.2f", n.ca));
		if (n.slots != null) {
			for (FlpNode s : n.slots) {
				if (s != null) {
					dump(s, depth + 1, out);
				}
			}
		}
	}

	// ---------------------------------------------------------------- 표시 목록

	/** goto: 정지 상태로 프레임 이동 (같은 프레임이면 스크립트 재실행 안 함). */
	void gotoFrame(FlpNode m, int f) {
		m.playing = false;
		f = Math.max(0, Math.min(f, m.totalFrames() - 1));
		if (f != m.frame) {
			showFrame(m, f);
		}
	}

	boolean gotoLabel(FlpNode m, String label) {
		if (m.kind != FlpNode.Kind.MOVIE) {
			return false;
		}
		for (FlpFile.FrameLabel l : m.timeline.labels()) {
			if (l.name() != null && l.name().equalsIgnoreCase(label)) {
				gotoFrame(m, l.frame());
				return true;
			}
		}
		return false;
	}

	/** 프레임 f 의 표시 목록 적용 + 프레임 스크립트 큐잉. */
	private void showFrame(FlpNode m, int f) {
		m.frame = f;
		// 부모 스크립트가 자식보다 먼저 (자식은 아래 표시 목록 갱신에서 큐잉)
		List<byte[]> scripts = scriptsOf(m.timeline).get(f);
		if (scripts != null) {
			for (byte[] s : scripts) {
				actions.add(new Object[]{m, s, f});
			}
		}
		List<FlpFile.Layer> layers = m.timeline.layers();
		for (int li = 0; li < layers.size(); li++) {
			List<FlpFile.KeyFrame> keys = layers.get(li).keys();
			int k = -1;
			for (int i = 0; i < keys.size() && keys.get(i).untilFrame() <= f; i++) {
				k = i;
			}
			FlpNode cur = m.slots[li];
			int handler = k < 0 ? -1 : keys.get(k).handler();
			FlpFile.Handler h = handler >= 0 && handler < flp.handlers.size() ? flp.handlers.get(handler) : null;
			if (h == null || h.type() == 0) {
				if (cur != null) {
					cur.markRemoved();
					m.slots[li] = null;
				}
				continue;
			}
			int seg = k;
			while (seg > 0 && keys.get(seg - 1).handler() == handler) {
				seg--;
			}
			if (cur == null || cur.handler != handler || cur.segment != seg) {
				if (cur != null) {
					cur.markRemoved();
				}
				cur = create(m, h, handler, seg);
				m.slots[li] = cur;
				String nm = null;
				for (int i = seg; i <= k && nm == null; i++) {
					nm = keys.get(i).name();
				}
				cur.name = nm;
				apply(cur, keys.get(k));
				if (cur != null && cur.kind == FlpNode.Kind.MOVIE) {
					showFrame(cur, 0);
				}
			} else {
				apply(cur, keys.get(k));
			}
		}
	}

	private FlpNode create(FlpNode parent, FlpFile.Handler h, int handler, int seg) {
		return switch (h.type()) {
			case 1 -> new FlpNode(FlpNode.Kind.MESH, handler, seg, parent, null, null, h.index());
			case 5 -> new FlpNode(FlpNode.Kind.TEXT, handler, seg, parent, null, null, h.index());
			case 6 -> {
				FlpFile.Sprite s = flp.sprites.get(h.index());
				yield new FlpNode(FlpNode.Kind.MOVIE, handler, seg, parent, s.timeline(), s, h.index());
			}
			case 7 -> new FlpNode(FlpNode.Kind.MOVIE, handler, seg, parent, flp.clips.get(h.index()), null, h.index());
			default -> new FlpNode(FlpNode.Kind.MESH, handler, seg, parent, null, null, -1);
		};
	}

	private void apply(FlpNode n, FlpFile.KeyFrame k) {
		if (k.transform() < flp.transforms.size()) {
			FlpFile.Transform t = flp.transforms.get(k.transform());
			n.a = t.a();
			n.b = t.b();
			n.c = t.c();
			n.d = t.d();
			n.tx = t.x();
			n.ty = t.y();
		}
		if (k.color() < flp.colors.size()) {
			int[] c = flp.colors.get(k.color());
			n.cr = c[0] / 256f;
			n.cg = c[1] / 256f;
			n.cb = c[2] / 256f;
			n.ca = c[3] / 256f;
		}
	}

	private Map<Integer, List<byte[]>> scriptsOf(FlpFile.Timeline t) {
		return frameScripts.computeIfAbsent(t, tl -> {
			Map<Integer, List<byte[]>> m = new java.util.HashMap<>();
			for (FlpFile.FrameLabel l : tl.labels()) {
				m.computeIfAbsent(l.frame(), x -> new ArrayList<>()).addAll(l.scripts());
			}
			return m;
		});
	}

	/** 라벨(또는 숫자 프레임 1 기반)의 스크립트 목록. */
	List<byte[]> labelScripts(FlpNode m, String label) {
		for (FlpFile.FrameLabel l : m.timeline.labels()) {
			if (l.name() != null && l.name().equalsIgnoreCase(label)) {
				return scriptsOf(m.timeline).getOrDefault(l.frame(), List.of());
			}
		}
		try {
			return scriptsOf(m.timeline).getOrDefault(Integer.parseInt(label.trim()) - 1, List.of());
		} catch (NumberFormatException e) {
			return List.of();
		}
	}

	// ---------------------------------------------------------------- 스크립트

	void run(byte[] code, FlpNode owner) {
		vm.execute(code, owner);
	}

	private void runActions() {
		int guard = 0;
		while (!actions.isEmpty() && guard++ < 10000) {
			Object[] a = actions.poll();
			FlpNode n = (FlpNode) a[0];
			// 실행 전에 다른 goto 로 프레임이 바뀌었으면 낡은 스크립트 (예: 새 자식의 frame 0 Stop 이 부모의 gotoAndPlay 를 덮어쓰는 것 방지)
			if (!n.removed && n.frame == (int) a[2]) {
				vm.execute((byte[]) a[1], n);
			}
		}
	}

	void fsCommand(String cmd, String arg) {
		if (trace) {
			System.out.println("[FLP] FSCommand " + cmd + " " + arg);
		}
		for (CommandListener l : commandListeners) {
			l.command(cmd, arg);
		}
	}

	double timeMillis() {
		return tickCount * 1000.0 / 60.0;
	}

	/** 슬래시 경로 해석: "/a/b", "_level0/a", "a/b"(base 기준), "..", "" = base. */
	FlpNode resolve(FlpNode base, String path) {
		if (path == null) {
			return base;
		}
		String p = path.trim();
		FlpNode n = base;
		if (p.startsWith("/")) {
			n = root;
			p = p.substring(1);
		} else if (p.regionMatches(true, 0, "_level0", 0, 7)) {
			n = root;
			p = p.substring(7);
		}
		for (String seg : p.split("/")) {
			if (seg.isEmpty() || seg.equals(".")) {
				continue;
			}
			if (seg.equals("..")) {
				n = n.parent != null ? n.parent : n;
			} else {
				n = n.child(seg);
				if (n == null) {
					return null;
				}
			}
		}
		return n;
	}

	/** 동적 라벨 노드의 표시 문자열 (변수는 라벨을 가진 클립 기준, 없으면 초기 텍스트). */
	String textOf(FlpNode n) {
		FlpFile.DynamicLabel dl = flp.dynamicLabels.get(n.index);
		String v = dl.variable() == null ? "" : vars.typed(dl.variable(), vm.getVar(n.parent, dl.variable())); // 메시지 타자 효과
		return v.isEmpty() && dl.placeholder() != null ? dl.placeholder() : v;
	}

	/** PS2_&lt;숫자&gt; 메시지. */
	String message(String var) {
		return messages != null ? messages.forVariable(var) : null;
	}
}
