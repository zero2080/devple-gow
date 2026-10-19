package devple.gow.tools.wad;

/** WAD 태그 ID. 분석 근거는 docs/02-wad-format.md 참고. */
public final class WadTagType {

	/** 정수 파라미터. 데이터 없음, size = 값 (EntityCount, HERO_HEAP_SIZE ...) */
	public static final int PARAM = 0x18;
	/** 리소스(서버 인스턴스). 데이터 첫 u32 가 리소스 매직 */
	public static final int RESOURCE = 0x1E;
	public static final int GROUP_START = 0x28;
	public static final int GROUP_END = 0x32;
	public static final int MC_DATA = 0x6E;
	/** 원시 파일 태그. MC_ICON(아이콘) 외에 메시지 테이블 msgsK.txt 도 이 태그로 들어 있다 (docs/07) */
	public static final int MC_ICON = 0x6F;
	/** 메시 부가 데이터 (MSH_, NCV_) */
	public static final int MESH_EXTRA = 0x70;
	/** 튜닝 텍스트 블록 (TWK_) */
	public static final int TWEAK = 0x71;
	public static final int TWEAK_REF = 0x72;
	public static final int RESOURCES = 0x1F4;
	/** 데이터 섹션 시작 */
	public static final int DATA_START = 0x29A;
	/** WAD 헤더. 데이터 u32 = 레벨 힙 크기 */
	public static final int HEADER = 0x378;
	public static final int POP_HEAP = 0x3E7;

	private WadTagType() {
	}

	public static String name(int tag) {
		return switch (tag) {
			case PARAM -> "PARAM";
			case RESOURCE -> "RES";
			case GROUP_START -> "GROUP{";
			case GROUP_END -> "}GROUP";
			case MC_DATA -> "MC_DATA";
			case MC_ICON -> "MC_ICON";
			case MESH_EXTRA -> "MESH_EX";
			case TWEAK -> "TWEAK";
			case TWEAK_REF -> "TWEAK_REF";
			case RESOURCES -> "RSRCS";
			case DATA_START -> "DATA_START";
			case HEADER -> "HEADER";
			case POP_HEAP -> "POP_HEAP";
			default -> String.format("0x%X", tag);
		};
	}
}
