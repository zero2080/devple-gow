package devple.gow.tools.video;

/**
 * 디코딩된 4:2:0 프레임 (표시 순서). y = width×height, u/v = (width/2)×(height/2), 0..255 (MPEG 제한 범위 16..235).
 * index = 표시 순서 번호, time = index / fps (초).
 */
public record VideoFrame(int index, double time, int width, int height, byte[] y, byte[] u, byte[] v) {
}
