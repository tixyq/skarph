package maze;

import java.util.Random;

/**
 * A perfect maze on a block grid: cells sit on odd block coordinates, walls between them are blocks too.
 * The grid is (2 * cellsX + 1) x (2 * cellsZ + 1) blocks. The start is the first cell, the goal is the farthest cell.
 * Pure Java, allocates only in the constructor and in generate()'s small local arrays.
 */
public final class Maze {
    public final int cellsX, cellsZ, w, h;
    private final boolean[] open;
    private final int[] dist, parent, queue;
    /** Block indices (bz * w + bx) of the solution from the start to the goal. */
    public final int[] path;
    public int pathLen;
    public int startBx = 1, startBz = 1, goalBx, goalBz;

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    public Maze(int cellsX, int cellsZ) {
        this.cellsX = cellsX;
        this.cellsZ = cellsZ;
        w = 2 * cellsX + 1;
        h = 2 * cellsZ + 1;
        open = new boolean[w * h];
        dist = new int[w * h];
        parent = new int[w * h];
        queue = new int[w * h];
        path = new int[w * h];
    }

    public boolean isWall(int bx, int bz) {
        if (bx < 0 || bz < 0 || bx >= w || bz >= h) return true;
        return !open[bz * w + bx];
    }

    public void generate(Random rnd) {
        java.util.Arrays.fill(open, false);
        boolean[] visited = new boolean[cellsX * cellsZ];
        int[] stack = new int[cellsX * cellsZ];
        int[] dirs = new int[4];
        int sp = 0;
        stack[sp++] = 0;
        visited[0] = true;
        open[startBz * w + startBx] = true;
        while (sp > 0) {
            int cur = stack[sp - 1];
            int cx = cur % cellsX, cz = cur / cellsX;
            int n = 0;
            for (int d = 0; d < 4; d++) {
                int nx = cx + DX[d], nz = cz + DZ[d];
                if (nx >= 0 && nz >= 0 && nx < cellsX && nz < cellsZ && !visited[nz * cellsX + nx]) dirs[n++] = d;
            }
            if (n == 0) {
                sp--;
                continue;
            }
            int d = dirs[rnd.nextInt(n)];
            int nx = cx + DX[d], nz = cz + DZ[d];
            open[(2 * cz + 1 + DZ[d]) * w + (2 * cx + 1 + DX[d])] = true;   // the wall between the two cells
            open[(2 * nz + 1) * w + (2 * nx + 1)] = true;
            visited[nz * cellsX + nx] = true;
            stack[sp++] = nz * cellsX + nx;
        }
        solve();
    }

    private void solve() {
        java.util.Arrays.fill(dist, -1);
        int head = 0, tail = 0;
        int s = startBz * w + startBx;
        dist[s] = 0;
        parent[s] = -1;
        queue[tail++] = s;
        int best = s;
        while (head < tail) {
            int cur = queue[head++];
            int bx = cur % w, bz = cur / w;
            if ((bx & 1) == 1 && (bz & 1) == 1 && dist[cur] > dist[best]) best = cur;
            for (int d = 0; d < 4; d++) {
                int nx = bx + DX[d], nz = bz + DZ[d];
                if (isWall(nx, nz)) continue;
                int ni = nz * w + nx;
                if (dist[ni] >= 0) continue;
                dist[ni] = dist[cur] + 1;
                parent[ni] = cur;
                queue[tail++] = ni;
            }
        }
        goalBx = best % w;
        goalBz = best / w;
        int len = dist[best] + 1;
        int i = best;
        for (int k = len - 1; k >= 0; k--) {
            path[k] = i;
            i = parent[i];
        }
        pathLen = len;
    }
}
