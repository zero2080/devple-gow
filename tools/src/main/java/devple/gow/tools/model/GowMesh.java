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
 *   0x2C u32 혼합 노드 수            ({@link #blendJoints}, 대부분 0)
 *   0x30 u32 혼합 표 단어 수
 *   0x34 u32 접촉 관절               (모델 부품 +0xE4 → [0] → +0x34: 스크립트 볼륨 접촉점 0x1ADD74 · 카메라 선택점 0x1EEFA0 — 영웅 1 = pelvis, {@link #contactJoint})
 *   0x38 char[] "main"             (나머지 0x3C~0x4F 는 미초기화 쓰레기 값이 섞여 있음)
 *   0x50 u32 partOffset[partCount]
 *   ...  20바이트 레코드 × recCount  {u32 (0xFFFF 또는 인덱스), f32 ×4} — 마지막 것이 모델 경계 구 (0x156884)
 *   ...  혼합 노드 표 (혼합 노드 수만큼 {u32 n, (u32 관절, f32 가중) × n})
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
	/**
	 * 혼합 행렬 노드 (헤더 +0x2C 개수, +0x30 표 단어 수) [확실: 데이터 — 전 WAD 메시 6288 개 중 12 개, 표 해석이 모두 단어 수와 맞고 가중 합 1]:
	 * 표는 20 바이트 레코드 바로 뒤 (0x50 + 4·파트 수 + 20·레코드 수) 에 노드마다 {u32 n, (u32 관절, f32 가중) × n}. 오브젝트 jointMap 의 번호
	 * {@link #jointCount} + i 는 노드 i = Σ 가중 × 관절 행렬 — 말하는 머리 (ATHN05B 제우스 얼굴 MAmagicDoor 32 개, ATHN02D/01E 아테나), 미노타우로스
	 * MIN 55/56 (05 문서의 "범위 밖 슬롯"), 키클롭스 CY3/CYW, 영웅 의상 1·5 … [추정: 원작 VU1 팔레트 코드는 못 찾음 — 데이터 일관성만]. 없으면 길이 0.
	 */
	public final int[][] blendJoints;
	public final float[][] blendWeights;
	/**
	 * 헤더 +0x34: 모델의 접촉 관절 — 원작 0x1ADD74 는 스크립트 레코드 점 (볼륨 진입·이탈 질의 0x1A6308) 을 모델 부품 +0xE4 (메시 목록) [0] +0x34 관절의
	 * 월드 위치로, 카메라 선택점 0x1EEFA0 도 같은 관절 [확실: 코드 + 데이터 hero_0 = 1 "pelvis"].
	 */
	public final int contactJoint;

	public GowMesh(String name, ByteBuffer data) {
		this.name = name;
		ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != MAGIC) {
			throw new IllegalArgumentException(String.format("메시 매직 아님: %08x", b.getInt(0)));
		}
		int partCount = b.getInt(0x08);
		recordCount = b.getInt(0x18);
		jointCount = b.getInt(0x28);
		contactJoint = b.limit() >= 0x38 ? b.getInt(0x34) : 0;
		int[][][] bl = blendNodes(b, partCount, recordCount, jointCount);
		blendJoints = bl[0];
		blendWeights = new float[blendJoints.length][];
		for (int i = 0; i < blendJoints.length; i++) {
			int[] wb = bl[1][i];
			blendWeights[i] = new float[wb.length];
			for (int k = 0; k < wb.length; k++) {
				blendWeights[i][k] = Float.intBitsToFloat(wb[k]);
			}
		}
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

	/**
	 * 혼합 노드 표 ({@link #blendJoints}) 해석: {관절[노드][], 가중 비트[노드][]}. 개수 (+0x2C) 가 0 이거나 표가 단어 수 (+0x30) · 관절 범위 ·
	 * 가중 합 1 에 맞지 않으면 빈 표 (예전처럼 짝 관절) — 헤더 +0x2C 는 혼합 노드가 없는 메시에서 0 이다.
	 */
	private static int[][][] blendNodes(ByteBuffer b, int partCount, int recordCount, int jointCount) {
		int[][][] none = {new int[0][], new int[0][]};
		if (b.limit() < 0x34) {
			return none;
		}
		int n = b.getInt(0x2C), words = b.getInt(0x30);
		if (n <= 0 || n > 1024 || words <= 0) {
			return none;
		}
		int off = 0x50 + 4 * partCount + 20 * recordCount, start = off;
		int[][] js = new int[n][], ws = new int[n][];
		for (int i = 0; i < n; i++) {
			if (off + 4 > b.limit()) {
				return none;
			}
			int c = b.getInt(off);
			off += 4;
			if (c < 1 || c > 8 || off + c * 8 > b.limit()) {
				return none;
			}
			js[i] = new int[c];
			ws[i] = new int[c];
			float sum = 0;
			for (int k = 0; k < c; k++) {
				js[i][k] = b.getInt(off);
				ws[i][k] = b.getInt(off + 4);
				sum += Float.intBitsToFloat(ws[i][k]);
				off += 8;
				if (js[i][k] < 0 || js[i][k] >= jointCount) {
					return none;
				}
			}
			if (Math.abs(sum - 1f) > 1e-3f) {
				return none;
			}
		}
		return (off - start) / 4 == words ? new int[][][]{js, ws} : none;
	}
}
