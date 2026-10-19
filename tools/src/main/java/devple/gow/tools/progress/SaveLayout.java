package devple.gow.tools.progress;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * 메모리카드 세이브 파일 {@code data0..4.bin} (0x14000 바이트) 의 구조.
 * <pre>
 * 버퍼 0x3387B8 (0x14000)
 *   [0x00000 .. 0x13FFB] 직렬화 스트림 (Save_Serialize 0x22D490 / Load_Deserialize 0x22D518, 압축 없음)
 *   [0x13FFC]            u32 체크섬 = 앞 0x4FFF 개 u32 의 합 (Save_Checksum 0x230160, 검증 0x2301A8)
 * </pre>
 */
public final class SaveLayout {

	public static final int FILE_SIZE = 0x14000;
	public static final int MAGIC = 0xCAFEBAD1;

	/** 스트림 블록 (기록 순서). */
	public record Block(String name, String size, String source, String desc) {
	}

	public static final List<Block> STREAM = List.of(
			new Block("magic", "4", "상수", "0xCAFEBAD1"),
			new Block("costume", "4", "HUD Costume_Choice (hud+0x1C4)", "로드 시 현재 R_HERO 코스튬과 다르면 영웅 WAD 재로드"),
			new Block("wadName[0]", "10+1", "0x1BBB88 (현재 레벨 WAD 스택)", "char[10] 이름 + u8 (활성 WAD/엔트리 추정)"),
			new Block("wadName[1]", "10+1", "〃", "중첩 WAD (추정)"),
			new Block("PlayerProgress", "0x90", "0x304440 → 0x306828", "PlayerProgress 레코드"),
			new Block("block2", "0xC0", "0x3068B8 (0x1EE438)", "카메라/연출 상태 (0x29F16C 객체, 미해석)"),
			new Block("block3", "0xEC", "0x306978 (0x22BD58)", "음악 그룹 상태 (0x2A26A8 = 음악 매니저 추정)"),
			new Block("GameMgr", "0x1C4", "0x3368F8 (0x1B15B8, memcpy)", "GameMgr 상태 (체크포인트/구역 정보, 미해석)"),
			new Block("options", "0xB", "0x306C30", "[0]0x1BF150() [1]Music [2]Sound [3]Vibration [4]WideScreen [5]DeFlicker [6]SoundMode "
					+ "[7]PowerUp_FirstTime [8]Costume [9]난이도(0x29DB54) [10]EndGame"),
			new Block("world", "가변", "0x1B3E48", "체크포인트 위치(0x29D920..), 현재 WAD 상태, GlobalData(0x2FFE38)/LevelData(0x301548) 변수, "
					+ "엔티티별 상태 청크 (0x1B4080/0x1B3FC0)"));

	/** 슬롯 디렉터리 엔트리 (0x35D780 + 카드×0x44 + 슬롯×0x10). 아이콘/슬롯 목록 표시용. */
	public static final List<PlayerProgress.Field> SLOT_ENTRY = List.of(
			new PlayerProgress.Field(0x0, "u32", "dataIndex", "data%d.bin 번호 (0x35D560 표)", true),
			new PlayerProgress.Field(0x4, "u32", "magic", "0xCAFEBAD1 이면 유효", true),
			new PlayerProgress.Field(0x8, "f32", "playTime", "PlayerProgress.playTime", true),
			new PlayerProgress.Field(0xC, "u16", "savePoint", "세이브 포인트 번호 (\"Save Point %d\")", false),
			new PlayerProgress.Field(0xE, "u8", "difficulty", "0 쉬움(인간) 1 보통(전사) 2 어려움(영웅) 3 매우 어려움(신)", true));

	/** 체크섬 계산 (마지막 워드 제외). */
	public static int checksum(ByteBuffer file) {
		ByteBuffer b = file.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int s = 0;
		for (int i = 0; i < 0x4FFF; i++) {
			s += b.getInt(i * 4);
		}
		return s;
	}

	public static boolean verify(ByteBuffer file) {
		int stored = file.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(0x4FFF * 4);
		return stored == 0 || stored == checksum(file);
	}

	private SaveLayout() {
	}
}
