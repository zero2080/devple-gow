package devple.gow.tools.script;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 엔티티 스크립트(ESC) 스택 머신 명령. 핸들러 시작 오프셋부터 opcode ≥ 0x3A(Exit) 까지 해석한다.
 * <pre>
 * 00 f32 push_float   01 s32 push_int    0E u16 push_string(코드 영역 기준 오프셋)
 * 02..05 u16 get_{float,int,bool,string} scope:id   06..09 u16 set_{...} scope:id   (u16 = scope&lt;&lt;12 | id)
 * 0A..0D u16 call → {float,int,bool,string}  scope:func
 * 0F s16 jump_if (pop != 0)   10 s16 jump  (둘 다 다음 명령 기준 상대)
 * 11 true  12 false  13/14 neg i/f  15..1C add/sub/mul/div (i,f 교대)  1D mod
 * 1E..23 형변환  24 not  25 and  26 or  27 xor
 * 28..37 비교 (lt, le, gt, ge, eq) × (int, float, str|bool)   38 return(pop)   3A.. exit
 * </pre>
 * scope: 0 Entity, 1 Internal(엔진 함수/변수), 2 GlobalData, 3 LevelData.
 */
public record EscOp(int offset, int code, String text) {

	public static final String[] SCOPES = {"Entity", "Internal", "GlobalData", "LevelData"};
	private static final String[] TYPES = {"float", "int", "bool", "string"};

	/** 함수 이름 (scope:id). mogaika 분석 기반 + 인자 패턴 대조, '?' 는 추정. */
	public static final Map<Integer, String> FUNCS = Map.ofEntries(
			Map.entry(0x0008, "Printf"),
			Map.entry(0x0009, "EntityRef0?"), Map.entry(0x000a, "EntityRef1?"),
			Map.entry(0x000b, "EntityRef2?"), Map.entry(0x000c, "EntityRef3?"),
			Map.entry(0x000d, "PlayStreamed?"), Map.entry(0x0010, "PreloadStreamed?"),
			Map.entry(0x1000, "CheckPoint"), Map.entry(0x1002, "Load?"), Map.entry(0x1003, "LoadWad?"),
			Map.entry(0x1004, "LoadForWarp?"), Map.entry(0x1005, "Warp?"), Map.entry(0x1006, "LoadCheck?"),
			Map.entry(0x1007, "Goto?"), Map.entry(0x1008, "SwitchCamera?"), Map.entry(0x1009, "AbortCutscene?"),
			Map.entry(0x100a, "ShowMessage(msgId,fSec?)"), Map.entry(0x100c, "Idle"),
			Map.entry(0x1010, "ZoneTitle?"), Map.entry(0x1013, "FailedScreen"),
			Map.entry(0x1014, "CreateTimer?"), Map.entry(0x1017, "DestroyTimer?"), Map.entry(0x1018, "TimerElapsed?"),
			Map.entry(0x1019, "StartTimer?"), Map.entry(0x101b, "MusicGroupStart"), Map.entry(0x101c, "MusicGroupStop"),
			Map.entry(0x101d, "MusicGroupParam?"), Map.entry(0x101f, "ShowMessage2?"),
			Map.entry(0x102f, "CompleteGame"), Map.entry(0x1038, "PlaySound?"));

	/** Internal scope 변수 이름 (mogaika). */
	public static final Map<Integer, String> INTERNAL_VARS = Map.of(
			44, "HealthShardsCount", 45, "MagicShardsCount", 48, "DifficultyLevel",
			50, "BladesLevel", 57, "SirenPiecesCount");

	public static String typeName(int t) {
		return t < TYPES.length ? TYPES[t] : "type" + t;
	}

	public static String scopeRef(int arg) {
		int scope = arg >>> 12, id = arg & 0xFFF;
		String s = (scope < SCOPES.length ? SCOPES[scope] : "S" + scope) + ":" + id;
		if (scope == 1 && INTERNAL_VARS.containsKey(id)) {
			s += "(" + INTERNAL_VARS.get(id) + ")";
		}
		return s;
	}

	public static String funcName(int arg) {
		String n = FUNCS.get(arg);
		return String.format("%s:0x%x", arg >>> 12 < SCOPES.length ? SCOPES[arg >>> 12] : "S" + (arg >>> 12), arg & 0xFFF)
				+ (n != null ? " " + n : "");
	}

	/** code 버퍼(바이트코드 영역)의 start 부터 Exit 까지 디코드. */
	public static List<EscOp> decode(ByteBuffer code, int start) {
		List<EscOp> out = new ArrayList<>();
		int p = start;
		while (p < code.limit()) {
			int at = p;
			int op = code.get(p++) & 0xFF;
			if (op >= 0x3A) {
				out.add(new EscOp(at, op, "exit"));
				break;
			}
			String t;
			try {
				switch (op) {
					case 0x00 -> {
						t = "push_float " + code.getFloat(p);
						p += 4;
					}
					case 0x01 -> {
						t = "push_int " + code.getInt(p);
						p += 4;
					}
					case 0x02, 0x03, 0x04, 0x05 -> {
						t = "get_" + TYPES[op - 2] + " " + scopeRef(u16(code, p));
						p += 2;
					}
					case 0x06, 0x07, 0x08, 0x09 -> {
						t = "set_" + TYPES[op - 6] + " " + scopeRef(u16(code, p));
						p += 2;
					}
					case 0x0A, 0x0B, 0x0C, 0x0D -> {
						t = "call_" + TYPES[op - 0x0A] + " " + funcName(u16(code, p));
						p += 2;
					}
					case 0x0E -> {
						t = "push_string \"" + EscScript.cstr(code, u16(code, p), 256) + "\"";
						p += 2;
					}
					case 0x0F, 0x10 -> {
						int off = code.getShort(p);
						p += 2;
						t = (op == 0x0F ? "jump_if " : "jump ") + String.format("@%04x", p + off);
					}
					default -> t = simple(op);
				}
			} catch (IndexOutOfBoundsException e) {
				t = "truncated";
				p = code.limit();
			}
			out.add(new EscOp(at, op, t));
		}
		return out;
	}

	private static int u16(ByteBuffer b, int p) {
		return Short.toUnsignedInt(b.getShort(p));
	}

	private static String simple(int op) {
		return switch (op) {
			case 0x11 -> "push_true";
			case 0x12 -> "push_false";
			case 0x13 -> "neg_i";
			case 0x14 -> "neg_f";
			case 0x15 -> "add_i";
			case 0x16 -> "add_f";
			case 0x17 -> "sub_i";
			case 0x18 -> "sub_f";
			case 0x19 -> "mul_i";
			case 0x1A -> "mul_f";
			case 0x1B -> "div_i";
			case 0x1C -> "div_f";
			case 0x1D -> "mod_i";
			case 0x1E -> "bool2float";
			case 0x1F -> "bool2int";
			case 0x20 -> "float2bool";
			case 0x21 -> "float2int";
			case 0x22 -> "int2bool";
			case 0x23 -> "int2float";
			case 0x24 -> "not";
			case 0x25 -> "and";
			case 0x26 -> "or";
			case 0x27 -> "xor";
			case 0x28 -> "lt_i";
			case 0x29 -> "lt_f";
			case 0x2A -> "lt_s";
			case 0x2B -> "le_i";
			case 0x2C -> "le_f";
			case 0x2D -> "le_s";
			case 0x2E -> "gt_i";
			case 0x2F -> "gt_f";
			case 0x30 -> "gt_s";
			case 0x31 -> "ge_i";
			case 0x32 -> "ge_f";
			case 0x33 -> "ge_s";
			case 0x34 -> "eq_i";
			case 0x35 -> "eq_f";
			case 0x36 -> "eq_b";
			case 0x37 -> "eq_s";
			case 0x38 -> "return";
			default -> String.format("?op%02X", op);
		};
	}
}
