package devple.gow.tools.model;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 메시 오브젝트 = 머티리얼 하나를 쓰는 VU1 DMA 패킷 묶음.
 * <pre>
 * 0x00 u32 type          0x0E(스키닝/일반), 0x1D(정적, STROW 오프셋 사용) — VU 프로그램 종류로 추정
 * 0x04 u32 dmaTagCount   체인 하나의 DMA 태그 수 (RET 포함)
 * 0x08 u16 materialIndex MDL 그룹의 MAT_ 참조 순서
 * 0x0A u16 jointCount    아래 jointMap 길이
 * 0x0C u32 chainCount    DMA 체인 수. 체인끼리 지오메트리 REF 는 같고 정점색 REF 만 다름(인스턴스별 베이크 라이팅)
 * 0x10 u32 ?             (0x10 고정)
 * 0x14 i32 flags?        (-219, -81 ... 하위 바이트 비트필드로 추정, 렌더 플래그)
 * 0x18 u8,u8,u16,u16,u16 (레이어 수? / 배치 수 / 정점 수 계열로 추정, 미확정)
 * 0x20 DMA 태그[chainCount][dmaTagCount]  (16바이트: 하위 64비트 DMAtag, 상위 64비트 VIF 코드 2개)
 *        REF(id 3): qwc, addr(오브젝트 시작 기준)  /  RET(id 6): 체인 끝
 * 이후 u32 jointMap[chainCount][jointCount]   스켈레톤 관절 인덱스 (체인=인스턴스별, 16바이트 정렬)
 * 이후 REF 가 가리키는 VIF 패킷들
 * </pre>
 */
public final class MeshObject {

	public final int offset;
	public final int type;
	public final int materialIndex;
	public final int chainCount;
	public final int dmaTagCount;
	public final int flags;
	public final int[] jointMap;
	/** 체인(인스턴스)별 jointMap. 체인 c 의 맵은 jointMap 영역에서 c*jointCount 번째부터 (05 문서). */
	public final int[][] jointMaps;
	public final List<VertexBatch> batches = new ArrayList<>();
	public final List<String> warnings = new ArrayList<>();

	public MeshObject(ByteBuffer b, int off) {
		this.offset = off;
		type = b.getInt(off);
		dmaTagCount = b.getInt(off + 4);
		materialIndex = Short.toUnsignedInt(b.getShort(off + 8));
		int jc = Short.toUnsignedInt(b.getShort(off + 10));
		chainCount = b.getInt(off + 12);
		flags = b.getInt(off + 0x14);
		int tagsEnd = off + 0x20 + chainCount * dmaTagCount * 16;
		jointMaps = new int[Math.max(1, chainCount)][jc];
		for (int c = 0; c < jointMaps.length; c++) {
			for (int i = 0; i < jc; i++) {
				jointMaps[c][i] = b.getInt(tagsEnd + (c * jc + i) * 4);
			}
		}
		jointMap = jointMaps[0];
		ByteBuffer stream = flattenChain(b, off, off + 0x20);
		List<VifParser.Batch> raw = VifParser.parse(stream, 0, stream.limit(), warnings);
		for (VifParser.Batch rb : raw) {
			VertexBatch vb = new VertexBatch(rb);
			warnings.addAll(vb.warnings);
			if (vb.count > 0) {
				batches.add(vb);
			}
		}
	}

	/**
	 * DMA 체인 0 을 VIF1 이 받는 순서대로 이어 붙인다.
	 * TTE=0 이므로 각 태그의 상위 64비트(VIF 코드 2개)가 먼저, 이어서 REF 데이터가 전송된다.
	 */
	private ByteBuffer flattenChain(ByteBuffer b, int base, int tagAt) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < Math.max(dmaTagCount, 1) && i < 4096; i++) {
			int q = tagAt + i * 16;
			int tag = b.getInt(q);
			int id = (tag >>> 28) & 7;
			int qwc = tag & 0xFFFF;
			write(out, b, q + 8, 8);
			switch (id) {
				case 3, 0 -> write(out, b, base + b.getInt(q + 4), qwc * 16); // REF, REFE
				case 1 -> write(out, b, q + 16, qwc * 16); // CNT (관찰되지 않음)
				case 6, 7 -> {
					return wrap(out);
				}
				default -> warnings.add(String.format("미처리 DMA 태그 id=%d @%x", id, q));
			}
			if (id == 0) {
				return wrap(out);
			}
		}
		return wrap(out);
	}

	private static void write(ByteArrayOutputStream out, ByteBuffer b, int at, int len) {
		byte[] tmp = new byte[len];
		b.get(at, tmp);
		out.writeBytes(tmp);
	}

	private static ByteBuffer wrap(ByteArrayOutputStream out) {
		return ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
	}

	public int vertexCount() {
		return batches.stream().mapToInt(v -> v.count).sum();
	}

	public int triangleCount() {
		return batches.stream().mapToInt(v -> v.triangles().length / 3).sum();
	}
}
