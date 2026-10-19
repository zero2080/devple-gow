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
 * ...  { u16 a,b,c, u16 ? } × triangleCount          (4번째 값 미확정: 면적/해시 계열로 추정)
 * </pre>
 * 같은 0x70 태그의 NCV_ 는 곡선(카메라/경로 스플라인) 데이터로 메시가 아니다.
 */
public final class MshMesh {

	public final String name;
	public final float[][] pos;
	public final float[][] normal;
	public final int[] tris;

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
		int to = 0x10 + nv * 24;
		for (int i = 0; i < nt; i++) {
			for (int k = 0; k < 3; k++) {
				tris[i * 3 + k] = Short.toUnsignedInt(b.getShort(to + i * 8 + k * 2));
			}
		}
	}
}
