package devple.gow.tools.wad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WAD 태그 레코드. 32바이트 헤더 + (size 바이트 데이터, 16바이트 정렬).
 * <pre>
 * 0x00 u16      tag     {@link WadTagType}
 * 0x02 u16      flags   리소스 태그(0x1E)에서는 서버 슬롯 인덱스로 추정
 * 0x04 u32      size    데이터 크기. 단 PARAM(0x18) 태그는 데이터가 없고 이 값 자체가 정수 값
 * 0x08 char[24] name
 * </pre>
 *
 * @param offset WAD 파일 내 헤더 오프셋
 * @param depth  GroupStart/GroupEnd 중첩 깊이
 * @param parent 같은 깊이에서 이 태그를 감싸는 그룹 직전의 리소스 태그 (없으면 null)
 */
public record WadTag(int offset, int tag, int flags, int size, String name, int depth, WadTag parent,
					 ByteBuffer file) {

	public static final int HEADER = 32;

	public boolean hasData() {
		return tag != WadTagType.PARAM && size > 0;
	}

	/** 데이터 영역 (little endian, position 0 기준 슬라이스). */
	public ByteBuffer data() {
		if (!hasData()) {
			return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
		}
		return file.slice(offset + HEADER, size).order(ByteOrder.LITTLE_ENDIAN);
	}

	/** 리소스 태그의 첫 u32 = 리소스 매직. */
	public int magic() {
		return size >= 4 && hasData() ? file.getInt(offset + HEADER) : 0;
	}

	public int nextOffset() {
		return tag == WadTagType.PARAM ? offset + HEADER : offset + HEADER + ((size + 15) & ~15);
	}
}
