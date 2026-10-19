package devple.gow.tools.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 메시 리소스 (매직 0x0001000F, 이름 {@code <모델>_0}, {@code _1} ...). MDL_(0x2000F) 그룹 안에 들어 있다.
 * <pre>
 * 헤더
 *   0x00 u32 magic 0x0001000F
 *   0x04 u32 데이터 크기 - 0x50 (끝 오프셋 추정)
 *   0x08 u32 partCount
 *   0x0C u32 ?, 0x10 u32 ?          (정점/스트립 총계로 추정, 미확정)
 *   0x14 u32 ?, 0x18 u32 recCount   (20바이트 레코드 수)
 *   0x20 u32 ?(0x10 정적 / 0x24 스키닝)
 *   0x28 u32 jointCount            (스켈레톤 관절 수; 정적 1. 히어로 111 = CXT hero 와 일치)
 *   0x38 char[] "main"             (나머지 0x2C~0x4F 는 미초기화 쓰레기 값이 섞여 있음)
 *   0x50 u32 partOffset[partCount]
 *   ...  20바이트 레코드 × recCount  {u32 (0xFFFF 또는 인덱스), f32 ×4}
 * 파트 (partOffset)
 *   u16 jointRefCount, u16 groupCount(LOD 수)
 *   u32 groupOffset[groupCount]     파트 기준
 *   u32 jointRef[jointRefCount]     파트가 붙는 관절
 * 그룹 = LOD (groupOffset)
 *   f32 lodDistance                 (0xD1000000 = 최상위 LOD, 그 외 음수 거리)
 *   u32 objectCount
 *   u32 hasBounds                   (1 이면 오브젝트 오프셋 뒤에 바운딩 구가 이어짐)
 *   u32 objectOffset[objectCount]   그룹 기준
 * </pre>
 * 오브젝트 구조는 {@link MeshObject}.
 */
public final class GowMesh {

	public static final int MAGIC = 0x0001000F;

	public record Group(float lodDistance, boolean hasBounds, List<MeshObject> objects) {
	}

	public record Part(int offset, int[] jointRefs, List<Group> groups) {
	}

	public final String name;
	public final int jointCount;
	public final int recordCount;
	public final List<Part> parts = new ArrayList<>();

	public GowMesh(String name, ByteBuffer data) {
		this.name = name;
		ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != MAGIC) {
			throw new IllegalArgumentException(String.format("메시 매직 아님: %08x", b.getInt(0)));
		}
		int partCount = b.getInt(0x08);
		recordCount = b.getInt(0x18);
		jointCount = b.getInt(0x28);
		for (int p = 0; p < partCount; p++) {
			int po = b.getInt(0x50 + p * 4);
			int jn = Short.toUnsignedInt(b.getShort(po));
			int gn = Short.toUnsignedInt(b.getShort(po + 2));
			int[] joints = new int[jn];
			for (int j = 0; j < jn; j++) {
				joints[j] = b.getInt(po + 4 + gn * 4 + j * 4);
			}
			List<Group> groups = new ArrayList<>();
			for (int g = 0; g < gn; g++) {
				int go = po + b.getInt(po + 4 + g * 4);
				int on = b.getInt(go + 4);
				List<MeshObject> objs = new ArrayList<>();
				for (int k = 0; k < on; k++) {
					objs.add(new MeshObject(b, go + b.getInt(go + 12 + k * 4)));
				}
				groups.add(new Group(b.getFloat(go), b.getInt(go + 8) != 0, objs));
			}
			parts.add(new Part(po, joints, groups));
		}
	}
}
