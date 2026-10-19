package devple.gow.tools.model;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 모델 리소스 MDL_ (매직 0x0002000F, 0x48 바이트) 와 WAD 그룹으로 묶인 의존 리소스.
 * <pre>
 * WAD 배치:  GROUP{ RES MDL_x (데이터)  RES MAT_a (size 0 참조) ...  RES x_0 (0x1000F 메시) [x_1 ...] }GROUP
 * MDL_ 데이터
 *   0x00 u32 magic 0x0002000F
 *   0x04 u32 0
 *   0x08 f32 ×3  (-2^31, 2^31, -2^31) 자리표시 값
 *   0x14 u32 materialCount   = 그룹 안 MAT_ 참조 수
 *   0x18 u32 meshCount       = 그룹 안 0x1000F 메시 수
 *   0x1C u32 jointCount      (메시 헤더 0x28 과 동일)
 *   0x20 u32 ?               (히어로 0x58, 소품 0/8)
 *   0x28 u64 렌더 플래그     (0x156820 이 모델 인스턴스 +0xC0 으로 복사, 43 문서 §9) — bit 57 = 반사 전용 (거울 뷰에서만),
 *                            bit 61 = 거울에도 비침 (0x1508D0 뷰 마스크), bit 56 = 글로우/플레어 계열 [추정]
 *   0x30 u64 플래그 마스크?  (대부분 0xFFFFFFFF_FFFFE7FB, bit 56/57 을 켠 모델은 그 비트를 뺀 값) [추정]
 *   0x24, 0x38~0x47          미확정 (float 값 + 쓰레기 값 혼재)
 * </pre>
 * 메시 오브젝트의 materialIndex 는 그룹 안 MAT_ 참조 순서를 가리킨다.
 */
public final class GowModel {

	public static final int MAGIC = 0x0002000F;

	public final String name;
	public final int materialCount;
	public final int meshCount;
	public final int jointCount;
	/** 렌더 플래그 (MDL +0x28 u64): {@link #REFLECT_ONLY} bit 57, {@link #REFLECTED} bit 61 (43 문서 §9). */
	public final long renderFlags;
	/** bit 57: 거울 뷰에서만 그린다 (가짜 반사 대리 메시 fakeReflections·refkect·…ReflectONLY) [확실: 0x1508D0]. */
	public static final long REFLECT_ONLY = 1L << 57;
	/** bit 61: 일반 뷰 + 거울 뷰 (반사에도 비침) [확실: 0x1508D0]. */
	public static final long REFLECTED = 1L << 61;
	public final List<String> materials = new ArrayList<>();
	public final List<GowMesh> meshes = new ArrayList<>();
	public final List<String> errors = new ArrayList<>();

	private GowModel(String name, ByteBuffer d) {
		this.name = name;
		materialCount = d.getInt(0x14);
		meshCount = d.getInt(0x18);
		jointCount = d.getInt(0x1C);
		renderFlags = d.limit() >= 0x30 ? d.getLong(0x28) : 0;
	}

	/** WAD 안의 모든 MDL_ 을 찾아 같은 그룹의 MAT 참조와 메시를 묶는다. */
	public static List<GowModel> load(WadFile wad) {
		List<GowModel> out = new ArrayList<>();
		List<WadTag> tags = wad.tags();
		for (int i = 0; i < tags.size(); i++) {
			WadTag t = tags.get(i);
			if (t.tag() != WadTagType.RESOURCE || !t.hasData() || t.magic() != MAGIC) {
				continue;
			}
			GowModel m = new GowModel(t.name(), t.data());
			for (int j = i + 1; j < tags.size(); j++) {
				WadTag s = tags.get(j);
				if (s.depth() < t.depth()) {
					break;
				}
				if (s.depth() != t.depth() || s.tag() != WadTagType.RESOURCE) {
					continue;
				}
				if (!s.hasData()) {
					if (s.name().startsWith("MAT_")) {
						m.materials.add(s.name());
					}
				} else if (s.magic() == GowMesh.MAGIC) {
					try {
						m.meshes.add(new GowMesh(s.name(), s.data()));
					} catch (RuntimeException e) {
						m.errors.add(s.name() + ": " + e);
					}
				} else if (s.magic() == MAGIC) {
					break;
				} else if (s.name().startsWith("MAT_")) {
					m.materials.add(s.name());
				}
			}
			out.add(m);
		}
		return out;
	}

	/** MAT_ 데이터 안의 TXR_ 이름들 (레이어 순서). MAT 포맷 자체는 03 문서 담당. */
	public static List<String> textureNames(WadFile wad, String matName) {
		List<String> r = new ArrayList<>();
		wad.find(matName).ifPresent(t -> {
			ByteBuffer d = t.data();
			for (int o = 0; o + 4 <= d.limit(); o += 4) {
				if (d.get(o) == 'T' && d.get(o + 1) == 'X' && d.get(o + 2) == 'R' && d.get(o + 3) == '_') {
					int len = 0;
					while (o + len < d.limit() && len < 24 && d.get(o + len) != 0) {
						len++;
					}
					byte[] s = new byte[len];
					d.get(o, s);
					r.add(new String(s, StandardCharsets.ISO_8859_1));
					o += (len + 3) & ~3;
					o -= 4;
				}
			}
		});
		return r;
	}
}
