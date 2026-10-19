package devple.gow.tools.script;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 스크립트 리소스(매직 0x00010004, 서버 SCRX). 이름은 {@code ESC_*}(엔티티 스크립트) 또는 {@code " SCR_*"}(환경 스크립트).
 * <pre>
 * 0x00 u32      magic 0x00010004
 * 0x04 char[16] target  실행 파일에 등록된 스크립트 클래스 이름 (SCR_Entities, SCR_Fog, SCR_Sky ...).
 *               16자를 넘으면 0x14 로 넘쳐 쓰인다 (SCR_ThingOnASpring)
 * 0x1e u16      항상 0x1388(5000)    0x20 u16 0x12|0x02|0x00    0x22 u16 0..2   (의미 미상)
 * 0x24 ...      target 별 본문. SCR_Entities = {@link Entity} 의 연속
 * </pre>
 */
public final class EscScript {

	public static final int MAGIC = 0x00010004;
	public static final int HEADER_SIZE = 0x24;

	public final String target;
	public final int unk1e, unk20, unk22;
	public final byte[] body;
	public final List<Entity> entities = new ArrayList<>();

	/**
	 * 엔티티 (트리거/센서/레벨 데이터 등 이벤트 노드). 엔티티 기준 오프셋:
	 * <pre>
	 * 0x00 f32[16] 월드 행렬     0x40 u16 미상   0x42 u16 미상
	 * 0x44 u16 엔티티 전체 크기  0x46 u16 type   0x48 u16 고유 ID  0x4a u16 물리/변수 기준 ID
	 * 0x4c u16 파라미터(LevelData/GlobalData 에서는 변수 수)
	 * 0x4e u16 핸들러 수 H       0x50 u16 대상 엔티티 수 T  0x52 u16 코드 영역 크기
	 * 0x54 (u16 id, u16 시작오프셋) × H, u16 대상ID × T, u16 0, 바이트코드(+문자열 상수)
	 * 그 뒤: 엔티티 이름 cstr, (Data 타입이면) 변수 [u8 type, cstr name] × 0x4c
	 * </pre>
	 */
	public record Entity(int type, int id, int baseId, int param, int unk40, float[] matrix, String name,
						 List<Integer> targets, List<Handler> handlers, List<String[]> variables, byte[] bytecode) {

		public String typeName() {
			return type < TYPE_NAMES.length ? TYPE_NAMES[type] : "Type" + type;
		}
	}

	/**
	 * 핸들러 = 이벤트/파라미터 번호 + 바이트코드. 상수 파라미터는 "Push x; Return; Exit" 형태다.
	 * start = {@link Entity#bytecode()} 안의 시작 오프셋 (VM 실행용).
	 */
	public record Handler(int id, int start, List<EscOp> code) {
	}

	/** 타입 이름은 mogaika 분석 + 엔티티 이름 접두사(...EventSensor, ...Vis 등) 대조. */
	public static final String[] TYPE_NAMES = {"EntrySensor", "ExitSensor", "CreationSensor", "DestructionSensor",
			"EventSensor", "Animator", "Type6", "Vis", "EventTransmitter", "Start", "SpawnEnemy", "Creator",
			"GlobalData", "LevelData", "Marker", "SoundController"};

	public EscScript(ByteBuffer data) {
		ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != MAGIC) {
			throw new IllegalArgumentException("not SCR: " + Integer.toHexString(b.getInt(0)));
		}
		target = cstr(b, 4, 0x1a);
		unk1e = Short.toUnsignedInt(b.getShort(0x1e));
		unk20 = Short.toUnsignedInt(b.getShort(0x20));
		unk22 = Short.toUnsignedInt(b.getShort(0x22));
		body = new byte[b.limit() - HEADER_SIZE];
		b.get(HEADER_SIZE, body);
		if (target.equals("SCR_Entities")) {
			int p = HEADER_SIZE;
			while (p + 0x54 <= b.limit()) {
				int size = Short.toUnsignedInt(b.getShort(p + 0x44));
				if (size == 0) {
					break;
				}
				entities.add(entity(b.slice(p, size).order(ByteOrder.LITTLE_ENDIAN)));
				p += size;
			}
		}
	}

	private static Entity entity(ByteBuffer e) {
		float[] m = new float[16];
		for (int i = 0; i < 16; i++) {
			m[i] = e.getFloat(i * 4);
		}
		int type = u16(e, 0x46), id = u16(e, 0x48), base = u16(e, 0x4a), param = u16(e, 0x4c);
		int nh = u16(e, 0x4e), nt = u16(e, 0x50), codeSize = u16(e, 0x52);
		int tStart = 0x54 + nh * 4;
		int codeStart = tStart + nt * 2 + 2;
		int textStart = 0x54 + nh * 4 + nt * 2 + codeSize;
		List<Integer> targets = new ArrayList<>();
		for (int i = 0; i < nt; i++) {
			targets.add(u16(e, tStart + i * 2));
		}
		int[] tp = {textStart};
		String name = next(e, tp);
		List<String[]> vars = new ArrayList<>();
		if (type == 12 || type == 13) {
			for (int i = 0; i < param && tp[0] < e.limit(); i++) {
				int vt = e.get(tp[0]++) & 0xFF;
				vars.add(new String[]{EscOp.typeName(vt), next(e, tp)});
			}
		}
		ByteBuffer code = e.slice(codeStart, e.limit() - codeStart).order(ByteOrder.LITTLE_ENDIAN);
		List<Handler> hs = new ArrayList<>();
		for (int i = 0; i < nh; i++) {
			int start = u16(e, 0x56 + i * 4);
			hs.add(new Handler(u16(e, 0x54 + i * 4), start, EscOp.decode(code, start)));
		}
		byte[] bc = new byte[code.limit()];
		code.get(0, bc);
		return new Entity(type, id, base, param, u16(e, 0x40), m, name, targets, hs, vars, bc);
	}

	private static int u16(ByteBuffer b, int p) {
		return Short.toUnsignedInt(b.getShort(p));
	}

	private static String next(ByteBuffer b, int[] p) {
		String s = cstr(b, p[0], b.limit() - p[0]);
		p[0] += s.length() + 1;
		return s;
	}

	static String cstr(ByteBuffer b, int off, int max) {
		int len = 0;
		while (len < max && off + len < b.limit() && b.get(off + len) != 0) {
			len++;
		}
		byte[] tmp = new byte[len];
		b.get(off, tmp);
		return new String(tmp, StandardCharsets.ISO_8859_1);
	}
}
