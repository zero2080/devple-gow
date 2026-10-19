package devple.gow.tools.level;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 카메라 CAM_ (CAM 서버 0x09, 0x24C 바이트). 소유 오브젝트 관절 로컬 좌표. 0xD0 이후는 툴 메모리 쓰레기.
 * <pre>
 * 0x00 u32 9          0x08 u16 0, u16 0xFFFF (IdleCam 류는 1)
 * 0x10 vec4[3]        회전 행 (행벡터, 관절 스케일의 역수만큼 스케일됨 → 관절 월드를 곱하면 정규직교)
 * 0x40 vec4           위치 (w=1)  — 카메라는 로컬 -Z 를 바라본다
 * 0x50 char[24]       레일 커브 이름 NCV_x (첫 바이트 0 이면 레일 없음, MESH_EXTRA 0x70 태그의 NCV_)
 * 0x70 f32 0.95       0x78 u16, u16 (모드/레일 종류 추정 1,1 / 1,3 / 2,2)
 * 0x80 f32[4]         (8, 8, 10, 10) / (1000, 0, 1000, 0) — 추적 거리/댐핑 추정
 * 0x90 f32[2]         (90, 85) / (30, 30) — FOV 후보 (도)
 * 0xAC f32 0.75       0xC8 f32 -5
 * </pre>
 */
public record CameraDef(String name, float[] local, String rail, int mode, int railKind, float[] params, float[] fov) {

	public static final int MAGIC = 0x00000009;

	public static CameraDef parse(String name, ByteBuffer d) {
		float[] m = new float[16];
		for (int r = 0; r < 4; r++) {
			for (int c = 0; c < 4; c++) {
				m[r * 4 + c] = d.getFloat(0x10 + r * 16 + c * 4);
			}
		}
		m[3] = m[7] = m[11] = 0;
		m[15] = 1;
		String rail = null;
		if (d.get(0x50) != 0) {
			int len = 0;
			while (len < 24 && d.get(0x50 + len) != 0) {
				len++;
			}
			byte[] b = new byte[len];
			d.get(0x50, b);
			rail = new String(b, StandardCharsets.ISO_8859_1);
		}
		float[] p = {d.getFloat(0x80), d.getFloat(0x84), d.getFloat(0x88), d.getFloat(0x8C)};
		float[] f = {d.getFloat(0x90), d.getFloat(0x94)};
		return new CameraDef(name, m, rail, d.getShort(0x78) & 0xFFFF, d.getShort(0x7A) & 0xFFFF, p, f);
	}
}
