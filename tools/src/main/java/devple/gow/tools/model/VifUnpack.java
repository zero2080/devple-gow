package devple.gow.tools.model;

/**
 * VIF UNPACK 명령 하나를 풀어낸 결과.
 * <pre>
 * cmd = 0x60 | m(0x10) | vn<<2 | vl
 *   vn: 0=S 1=V2 2=V3 3=V4 (성분 수 - 1)
 *   vl: 0=32bit 1=16bit 2=8bit 3=5bit(V4-5 = RGBA5551 16비트)
 * imm = addr(10bit) | usn(bit14, 0이면 부호 확장) | flg(bit15, TOPS 기준 주소)
 * </pre>
 *
 * @param vn     성분 수 - 1
 * @param vl     비트 폭 코드
 * @param addr   VU 메모리 쿼드워드 주소 (flg=1 이면 TOPS 상대)
 * @param usn    부호 없는 확장 여부
 * @param flg    TOPS 상대 주소 여부
 * @param cl     STCYCL CL (쓰기 간격). 정점 하나가 차지하는 쿼드워드 수로 쓰인다
 * @param wl     STCYCL WL
 * @param count  원소(정점) 수
 * @param comps  원소당 성분 수 (V4-5 는 4)
 * @param data   count*comps 개 정수. 부호/0 확장 + STMOD(offset/difference) 적용 후 값
 * @param offset 원본 스트림 내 명령 위치 (디버그용)
 * @param row    STMOD 1(offset) 로 더해진 STROW 값 (없으면 null). 정적 메시 위치의 툴 오프셋 — 게임은 쓰지 않는다 (12 문서)
 */
public record VifUnpack(int vn, int vl, int addr, boolean usn, boolean flg, int cl, int wl, int count, int comps,
						int[] data, int offset, int[] row) {

	public static final int VL_32 = 0;
	public static final int VL_16 = 1;
	public static final int VL_8 = 2;
	public static final int VL_5 = 3;

	/** "V4-16" 같은 표기. */
	public String format() {
		if (vl == VL_5) {
			return "V4-5";
		}
		String[] n = {"S", "V2", "V3", "V4"};
		int[] b = {32, 16, 8};
		return n[vn] + "-" + b[vl];
	}

	public int get(int index, int comp) {
		return data[index * comps + comp];
	}

	/** 32비트 성분을 float 비트로 해석. */
	public float getFloat(int index, int comp) {
		return Float.intBitsToFloat(data[index * comps + comp]);
	}

	public boolean is(int vn, int vl) {
		return this.vn == vn && this.vl == vl;
	}
}
