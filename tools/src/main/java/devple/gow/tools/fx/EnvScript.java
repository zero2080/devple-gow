package devple.gow.tools.fx;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 환경 스크립트 리소스 (매직 0x10004, target = SCR_Fog / SCR_LayeredFog / SCR_FarClip / SCR_Sky / SCR_Weather / SCR_Water / SCR_Mirror …).
 * 16 문서 §1. 0x24 헤더 뒤 본문 = 파라미터 값.
 * <pre>
 * 헤더 0x20 u16 bit4(0x10) = 인스턴스 값 (go 인스턴스 그룹 안, 실제로 쓰이는 값)
 *              0          = 템플릿 (MDL 그룹 안: u32 n, u32 오프셋[n], 이어서 같은 배치의 기본값)
 * </pre>
 *
 * @param owner    소유 리소스 이름 (인스턴스면 go 인스턴스, 템플릿이면 MDL_)
 * @param instance true = 인스턴스 값
 * @param values   파라미터 값 영역 (little endian, position 0 = 첫 값)
 */
public record EnvScript(String target, String owner, boolean instance, ByteBuffer values) {

	public static final int MAGIC = 0x00010004;

	/** 본문 파싱. 환경 스크립트가 아니면 null. */
	public static EnvScript parse(WadTag t, String owner) {
		if (t.tag() != WadTagType.RESOURCE || !t.hasData() || t.magic() != MAGIC || t.size() < 0x24) {
			return null;
		}
		ByteBuffer d = t.data();
		String target = cstr(d, 4, 26);
		if (target.equals("SCR_Entities")) {
			return null;
		}
		boolean inst = (Short.toUnsignedInt(d.getShort(0x20)) & 0x10) != 0;
		ByteBuffer body = d.slice(0x24, d.limit() - 0x24).order(ByteOrder.LITTLE_ENDIAN);
		if (!inst && body.limit() >= 4) {
			int n = body.getInt(0);
			int start = 4 + 4 * n;
			if (n > 0 && n < 64 && start <= body.limit()) {
				body = body.slice(start, body.limit() - start).order(ByteOrder.LITTLE_ENDIAN);
			}
		}
		return new EnvScript(target, owner, inst, body);
	}

	/** WAD 의 모든 환경 스크립트 (등장 순서). */
	public static List<EnvScript> load(WadFile wad) {
		List<EnvScript> out = new ArrayList<>();
		List<WadTag> tags = wad.tags();
		for (int k = 0; k < tags.size(); k++) {
			EnvScript s = parse(tags.get(k), owner(tags, k));
			if (s != null) {
				out.add(s);
			}
		}
		return out;
	}

	/**
	 * 소유 리소스: 같은 그룹의 바로 앞 리소스 (인스턴스: {@code GROUP{ go인스턴스, SCR }}).
	 * 바로 앞이 그룹 시작이면 그룹 앞쪽의 데이터 있는 리소스 (템플릿: {@code MDL_x, MAT 참조, GROUP{ SCR }}).
	 */
	static String owner(List<WadTag> tags, int k) {
		int j = k - 1;
		if (j >= 0 && tags.get(j).tag() == WadTagType.RESOURCE) {
			return tags.get(j).name();
		}
		for (j = k - 2; j >= 0; j--) {
			WadTag t = tags.get(j);
			if (t.tag() == WadTagType.RESOURCE && t.hasData()) {
				return t.name();
			}
		}
		return "";
	}

	public float f(int off) {
		return off + 4 <= values.limit() ? values.getFloat(off) : 0f;
	}

	public int i(int off) {
		return off + 4 <= values.limit() ? values.getInt(off) : 0;
	}

	public float[] vec(int off, int n) {
		float[] r = new float[n];
		for (int k = 0; k < n; k++) {
			r[k] = f(off + 4 * k);
		}
		return r;
	}

	public String str(int off, int max) {
		return off < values.limit() ? cstr(values, off, Math.min(max, values.limit() - off)) : "";
	}

	public int size() {
		return values.limit();
	}

	static String cstr(ByteBuffer b, int off, int max) {
		int n = 0;
		while (n < max && off + n < b.limit() && b.get(off + n) != 0) {
			n++;
		}
		byte[] a = new byte[n];
		b.get(off, a);
		return new String(a, StandardCharsets.ISO_8859_1);
	}
}
