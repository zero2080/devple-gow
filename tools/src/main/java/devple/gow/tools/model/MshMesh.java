package devple.gow.tools.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * MESH_EXTRA(0x70) 태그의 MSH_ 레코드: 파티클 방출면 등에 쓰는 float 삼각형 메시 (렌더용 아님).
 * <pre>
 * 0x00 u32 vertexCount
 * 0x04 u32 triangleCount
 * 0x08 u32 ×2 0xFFFFFFFF
 * 0x10 { f32 x,y,z, f32 nx,ny,nz } × vertexCount   (월드 좌표, ÷16 없이 그대로)
 * ...  { u16 a,b,c, u16 누적 면적 } × triangleCount   (4번째 = 이 삼각형까지의 면적 합 / 전체 × 65535, 오름차순)
 * </pre>
 * 4번째 값 [확실: 표면 이미터 생성 0x1252E0 이 난수 × 65535 (0x125328 – 0x125364) 보다 작은 동안 다음 삼각형으로 넘어가 (+6 비교, 0x125368 – 0x12538C)
 * 면적에 비례해 삼각형을 고른다 — R_BRSRK0 MSH_berzergeomShape1 668, 1337, 2621 …].
 * 같은 0x70 태그의 NCV_ 는 곡선(카메라/경로 스플라인) 데이터로 메시가 아니다.
 */
public final class MshMesh {

	public final String name;
	public final float[][] pos;
	public final float[][] normal;
	public final int[] tris;
	/** 삼각형마다 누적 면적 (u16, 마지막 ≈ 65535) — 표면 이미터가 면적 비례로 고른다 (0x1252E0). */
	public final int[] cumArea;

	public MshMesh(String name, ByteBuffer data) {
		this.name = name;
		ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int nv = b.getInt(0);
		int nt = b.getInt(4);
		if (nv < 0 || nt < 0 || 0x10 + nv * 24L + nt * 8L > b.limit()) {
			throw new IllegalArgumentException("MSH 크기 불일치 nv=" + nv + " nt=" + nt + " size=" + b.limit());
		}
		pos = new float[nv][3];
		normal = new float[nv][3];
		for (int i = 0; i < nv; i++) {
			for (int k = 0; k < 3; k++) {
				pos[i][k] = b.getFloat(0x10 + i * 24 + k * 4);
				normal[i][k] = b.getFloat(0x10 + i * 24 + 12 + k * 4);
			}
		}
		tris = new int[nt * 3];
		cumArea = new int[nt];
		int to = 0x10 + nv * 24;
		for (int i = 0; i < nt; i++) {
			for (int k = 0; k < 3; k++) {
				tris[i * 3 + k] = Short.toUnsignedInt(b.getShort(to + i * 8 + k * 2));
			}
			cumArea[i] = Short.toUnsignedInt(b.getShort(to + i * 8 + 6));
		}
	}
}
