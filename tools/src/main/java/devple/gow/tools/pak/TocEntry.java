package devple.gow.tools.pak;

/**
 * GODOFWAR.TOC 엔트리 (24바이트).
 * <pre>
 * 0x00 char[12] name    (NUL 패딩, 12자 꽉 차면 NUL 없음)
 * 0x0C u32      part    n = PART<n+1>.PAK (한글판: 0 = PART1.PAK 레이어 0, 1 = PART2.PAK 레이어 1)
 * 0x10 u32      size    바이트
 * 0x14 u32      sector  해당 PART 파일 내 2048바이트 섹터 오프셋
 * </pre>
 * 같은 이름이 여러 번 등장한다. 레벨 로딩 시 탐색 거리를 줄이려고 디스크 여러 위치에 복제한 것이다.
 */
public record TocEntry(String name, int part, long size, long sector) {
}
