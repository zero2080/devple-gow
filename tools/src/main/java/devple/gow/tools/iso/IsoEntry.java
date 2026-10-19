package devple.gow.tools.iso;

/**
 * ISO9660 디렉토리 레코드 하나.
 *
 * @param path   볼륨 루트 기준 경로 (예: "/SCKA_200.84")
 * @param lba    볼륨 시작 기준 논리 섹터 번호
 * @param size   바이트 크기
 * @param dir    디렉토리 여부
 * @param layer  DVD9 레이어 (0 또는 1)
 */
public record IsoEntry(String path, long lba, long size, boolean dir, int layer) {
}
