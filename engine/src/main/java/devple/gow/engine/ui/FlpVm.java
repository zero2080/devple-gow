package devple.gow.engine.ui;

import devple.gow.tools.ui.FlpFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FLP 액션 스크립트 인터프리터 (SWF4 액션 변형, 07 문서 §4.3).
 * 값은 플래시 4 처럼 전부 문자열이고 산술 시 숫자로 변환한다 (숫자가 아니면 0, 미정의 = "").
 * opcode 0x80 이상은 길이 필드 없이 고정 인자, 문자열은 FLP 문자열 섹션 u16 오프셋.
 */
final class FlpVm {

	private static final int MAX_DEPTH = 32;

	private final FlpPlayer player;
	private int depth;

	FlpVm(FlpPlayer player) {
		this.player = player;
	}

	// ---------------------------------------------------------------- 값 변환

	static double toNumber(String s) {
		if (s == null || s.isEmpty()) {
			return 0;
		}
		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	static String format(double v) {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			return "0";
		}
		if (v == Math.rint(v) && Math.abs(v) < 1e15) {
			return Long.toString((long) v);
		}
		String s = Double.toString(v);
		return s.contains("E") ? String.format("%.6f", v) : s;
	}

	private static String bool(boolean b) {
		return b ? "1" : "0";
	}

	// ---------------------------------------------------------------- 실행

	void execute(byte[] code, FlpNode owner) {
		if (depth >= MAX_DEPTH) {
			return;
		}
		depth++;
		try {
			exec(code, owner);
		} catch (RuntimeException e) {
			System.err.println("[FLP] 스크립트 오류 @" + owner + ": " + e);
		} finally {
			depth--;
		}
	}

	private void exec(byte[] code, FlpNode owner) {
		FlpFile flp = player.flp;
		List<String> st = new ArrayList<>();
		FlpNode target = owner;
		int p = 0;
		while (p < code.length) {
			int op = code[p++] & 0xFF;
			if (op >= 0x80) {
				switch (op) {
					case 0x81 -> {
						int f = u16(code, p);
						p += 2;
						if (target != null) {
							player.gotoFrame(target, f);
						}
					}
					case 0x83 -> {
						String url = flp.str(u16(code, p));
						String tgt = flp.str(u16(code, p + 2));
						p += 4;
						getUrl(url, tgt);
					}
					case 0x8B -> {
						String path = flp.str(u16(code, p));
						p += 2;
						target = path == null || path.isEmpty() ? owner : player.resolve(owner, path);
					}
					case 0x8C -> {
						String label = flp.str(u16(code, p));
						p += 2;
						if (target != null) {
							player.gotoLabel(target, label);
						}
					}
					case 0x96 -> {
						String v = flp.str(u16(code, p));
						p += 2;
						st.add(v != null ? v : "");
					}
					case 0x99 -> {
						int off = (short) u16(code, p);
						p += 2 + off;
					}
					case 0x9D -> {
						int off = (short) u16(code, p);
						p += 2;
						if (toNumber(pop(st)) != 0) {
							p += off;
						}
					}
					case 0x9A -> {
						p++;
						String tgt = pop(st);
						String url = pop(st);
						// loadVariables("dynK.txt", "/"): 메시지는 PS2_<ID> 조회로 대신한다
						getUrl(url, tgt);
					}
					case 0x9E -> call(target != null ? target : owner, pop(st));
					case 0x9F -> {
						boolean play = (code[p++] & 0xFF) != 0;
						String f = pop(st);
						if (target != null) {
							goto2(target, f, play);
						}
					}
					default -> {
						System.err.printf("[FLP] 미지 opcode %02x%n", op);
						return;
					}
				}
				continue;
			}
			switch (op) {
				case 0x00 -> {
					return;
				}
				case 0x04, 0x05 -> {
					if (target != null) {
						player.gotoFrame(target, target.frame + (op == 0x04 ? 1 : -1));
					}
				}
				case 0x06 -> {
					if (target != null) {
						target.playing = true;
					}
				}
				case 0x07 -> {
					if (target != null) {
						target.playing = false;
					}
				}
				case 0x08, 0x09 -> {
				}
				case 0x0A, 0x0B, 0x0C, 0x0D -> {
					double b = toNumber(pop(st));
					double a = toNumber(pop(st));
					st.add(format(switch (op) {
						case 0x0A -> a + b;
						case 0x0B -> a - b;
						case 0x0C -> a * b;
						default -> b == 0 ? 0 : a / b;
					}));
				}
				case 0x0E -> {
					double b = toNumber(pop(st));
					st.add(bool(toNumber(pop(st)) == b));
				}
				case 0x0F -> {
					double b = toNumber(pop(st));
					st.add(bool(toNumber(pop(st)) < b));
				}
				case 0x10 -> {
					double b = toNumber(pop(st));
					st.add(bool(toNumber(pop(st)) != 0 && b != 0));
				}
				case 0x11 -> {
					double b = toNumber(pop(st));
					st.add(bool(toNumber(pop(st)) != 0 || b != 0));
				}
				case 0x12 -> st.add(bool(toNumber(pop(st)) == 0));
				case 0x13 -> {
					String b = pop(st);
					st.add(bool(pop(st).equals(b)));
				}
				case 0x14 -> st.add(Integer.toString(pop(st).length()));
				case 0x15 -> {
					int count = (int) toNumber(pop(st));
					int index = (int) toNumber(pop(st)) - 1;
					String s = pop(st);
					int from = Math.max(0, Math.min(index, s.length()));
					st.add(s.substring(from, Math.max(from, Math.min(s.length(), from + count))));
				}
				case 0x17 -> pop(st);
				case 0x18 -> st.add(format((long) toNumber(pop(st))));
				case 0x1C -> st.add(getVar(target != null ? target : owner, pop(st)));
				case 0x1D -> {
					String v = pop(st);
					setVar(target != null ? target : owner, pop(st), v);
				}
				case 0x20 -> {
					String path = pop(st);
					target = path.isEmpty() ? owner : player.resolve(owner, path);
				}
				case 0x21 -> {
					String b = pop(st);
					st.add(pop(st) + b);
				}
				case 0x22 -> {
					int idx = (int) toNumber(pop(st));
					FlpNode n = player.resolve(owner, pop(st));
					st.add(n == null ? "" : getProperty(n, idx));
				}
				case 0x23 -> {
					String v = pop(st);
					int idx = (int) toNumber(pop(st));
					FlpNode n = player.resolve(owner, pop(st));
					if (n != null) {
						setProperty(n, idx, toNumber(v));
					}
				}
				case 0x26 -> System.out.println("[FLP trace] " + pop(st));
				case 0x29 -> {
					String b = pop(st);
					st.add(bool(pop(st).compareTo(b) < 0));
				}
				case 0x30 -> {
					int max = (int) toNumber(pop(st));
					st.add(Integer.toString(max <= 0 ? 0 : ThreadLocalRandom.current().nextInt(max)));
				}
				case 0x34 -> st.add(format(Math.floor(player.timeMillis())));
				default -> System.err.printf("[FLP] 미지원 액션 %02x%n", op);
			}
		}
	}

	private static String pop(List<String> st) {
		return st.isEmpty() ? "" : st.removeLast();
	}

	private static int u16(byte[] c, int p) {
		return (c[p] & 0xFF) | (c[p + 1] & 0xFF) << 8;
	}

	private void getUrl(String url, String target) {
		if (url != null && url.regionMatches(true, 0, "FSCommand:", 0, 10)) {
			player.fsCommand(url.substring(10), target);
		}
	}

	/** GotoFrame2: 숫자는 1 기반 프레임 번호, 문자열은 "[경로:]라벨". */
	private void goto2(FlpNode target, String f, boolean play) {
		FlpNode n = target;
		String frame = f;
		int colon = f.lastIndexOf(':');
		if (colon >= 0) {
			n = player.resolve(target, f.substring(0, colon));
			frame = f.substring(colon + 1);
		}
		if (n == null || n.kind != FlpNode.Kind.MOVIE) {
			return;
		}
		boolean numeric = !frame.isEmpty() && (Character.isDigit(frame.charAt(0)) || frame.charAt(0) == '-');
		if (numeric) {
			player.gotoFrame(n, (int) Math.floor(toNumber(frame)) - 1);
		} else if (!player.gotoLabel(n, frame)) {
			return;
		}
		n.playing = play;
	}

	/** Call: "[경로:]라벨" 의 프레임 스크립트를 그 클립 문맥에서 즉시 실행 (재생 위치는 그대로). */
	void call(FlpNode base, String pathLabel) {
		FlpNode n = base;
		String label = pathLabel;
		int colon = pathLabel.lastIndexOf(':');
		if (colon >= 0) {
			n = player.resolve(base, pathLabel.substring(0, colon));
			label = pathLabel.substring(colon + 1);
		}
		if (n == null || n.kind != FlpNode.Kind.MOVIE) {
			return;
		}
		for (byte[] s : player.labelScripts(n, label)) {
			execute(s, n);
		}
	}

	// ---------------------------------------------------------------- 변수

	/** "[경로:]이름". PS2_* 는 엔진 공유 변수, PS2_&lt;숫자&gt; 는 미설정 시 메시지 테이블. */
	String getVar(FlpNode base, String ref) {
		int colon = ref.lastIndexOf(':');
		FlpNode n = base;
		String name = ref;
		if (colon >= 0) {
			n = player.resolve(base, ref.substring(0, colon));
			name = ref.substring(colon + 1);
		}
		if (UiVariables.isGlobal(name)) {
			String v = player.vars.get(name);
			if (v == null) {
				v = player.message(name);
			}
			return v != null ? v : "";
		}
		if (n == null || n.vars == null) {
			return "";
		}
		String v = n.vars.get(name);
		return v != null ? v : "";
	}

	void setVar(FlpNode base, String ref, String value) {
		int colon = ref.lastIndexOf(':');
		FlpNode n = base;
		String name = ref;
		if (colon >= 0) {
			n = player.resolve(base, ref.substring(0, colon));
			name = ref.substring(colon + 1);
		}
		if (UiVariables.isGlobal(name)) {
			player.vars.setFromScript(name, value);
		} else if (n != null && n.vars != null) {
			n.vars.put(name, value);
		}
	}

	// ---------------------------------------------------------------- 속성 (SWF4 인덱스)

	private static String getProperty(FlpNode n, int idx) {
		return switch (idx) {
			case 0 -> format(n.tx);
			case 1 -> format(n.ty);
			case 2 -> format(n.a * 100);
			case 3 -> format(n.d * 100);
			case 4 -> format(n.frame + 1);
			case 5 -> format(n.kind == FlpNode.Kind.MOVIE ? n.totalFrames() : 1);
			case 6 -> format(n.ca * 100);
			case 7 -> "1";
			case 13 -> n.name != null ? n.name : "";
			default -> "";
		};
	}

	private static void setProperty(FlpNode n, int idx, double v) {
		switch (idx) {
			case 0 -> n.tx = (float) v;
			case 1 -> n.ty = (float) v;
			case 2 -> n.a = (float) (v / 100);
			case 3 -> n.d = (float) (v / 100);
			case 6 -> n.ca = (float) (v / 100);
			default -> {
			}
		}
	}
}
