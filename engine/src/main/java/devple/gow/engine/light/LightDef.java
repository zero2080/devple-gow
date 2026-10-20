package devple.gow.engine.light;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * WAD 라이트 리소스 LGT_ (매직 6, 0x58 바이트, LightServer 타입 6) — 47 문서 §1.
 * 생성자 0x1514C0 이 파일 필드를 런타임 오브젝트 (0xF0 바이트 풀, 0x2417C0) 로 옮긴다 [확실: 디스어셈블]:
 * <pre>
 * 0x08 u32  종류        → +0xC0  비트 1 = 점 (방향 = 라이트 − 물체), 2 = 방향 있음 (0x151978 정규화), 4 = 그림자 방향에 더함, 8 = 전역 목록
 *                                (0 / 8 = 주변광)
 * 0x0C vec4 위치        → +0x10  (w = 1)
 * 0x1C vec4 방향        → +0x20  (물체 → 라이트 쪽, 위가 +y)
 * 0x2C vec3 색, 0x38 I0 → +0x30  (w = 중심 세기 I0)
 * 0x3C r0               → +0xC4  안쪽 반경
 * 0x40 I1               → +0xC8  안쪽 반경에서의 세기
 * 0x44 r1               → +0xCC  범위 (여기서 0)
 * 0x48 u32 그룹         → +0xD0 (short) 0 = 슬롯 0, 그 밖 = 슬롯 1
 * 0x4C, 0x50 원뿔       → 종류 비트 1·2 면 0x151978 (+0xD4/+0xD8/+0xDC) — 모으기 0x1521B0 는 쓰지 않음
 * 0x54 탈색            → +0xE4  (물체 렌더 플래그 8 일 때만, 디스크 전부 0)
 *                         +0xE0 = 1 동적 세기 배율 (블레이드 라이트 켜고 끔)
 * </pre>
 */
public record LightDef(String name, int type, float[] pos, float[] dir, float[] color, float i0, float r0, float i1, float r1,
					   int group, float coneA, float coneB, float desat) {

	public static final int MAGIC = 6;
	public static final int SIZE = 0x58;

	public static final int POINT = 1, DIRECTIONAL = 2, SHADOW = 4, GLOBAL = 8;

	/** 주변광 (0x1524A0: 종류 0 또는 8). */
	public boolean ambient() {
		return type == 0 || type == GLOBAL;
	}

	public static LightDef parse(String name, ByteBuffer d) {
		float[] pos = {d.getFloat(0x0C), d.getFloat(0x10), d.getFloat(0x14)};
		float[] dir = {d.getFloat(0x1C), d.getFloat(0x20), d.getFloat(0x24)};
		float[] col = {d.getFloat(0x2C), d.getFloat(0x30), d.getFloat(0x34)};
		return new LightDef(name, d.getInt(0x08), pos, dir, col, d.getFloat(0x38), d.getFloat(0x3C), d.getFloat(0x40), d.getFloat(0x44),
				d.getInt(0x48), d.getFloat(0x4C), d.getFloat(0x50), d.getFloat(0x54));
	}

	/** WAD 의 모든 LGT_ (리소스 태그 0x1E, 매직 6, 크기 0x58). */
	public static List<LightDef> load(WadFile wad) {
		List<LightDef> out = new ArrayList<>();
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.RESOURCE && t.hasData() && t.size() >= SIZE && t.magic() == MAGIC) {
				out.add(parse(t.name(), t.data()));
			}
		}
		return out;
	}
}
