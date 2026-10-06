import java.io.*;
import java.util.*;

public class sim_cache {

    static int BLOCKSIZE;
    static int L1_SIZE;
    static int L1_ASSOC;
    static int L2_SIZE;
    static int L2_ASSOC;
    static int REPLACEMENT_POLICY;   
    static int INCLUSION_PROPERTY;   
    static String trace_file;

    static long L1_read = 0;
    static long L1_readMiss = 0;
    static long L1_writes = 0;
    static long L1_writeMiss = 0;
    static long L1_hits = 0;
    static long L1_writebacks = 0;

    static long L2_reads = 0;
    static long L2_readMiss = 0;
    static long L2_writes = 0;
    static long L2_writeMiss = 0;
    static long L2_hits = 0;
    static long L2_writebacks = 0;

    static long inclusiveWritebackCount = 0;

    static class Line {
        boolean valid = false;
        int tag = 0;
        boolean dirty = false;
        int age = 0;   
    }
    static class Cache {
        int size;
        int assoc;
        int blockSize;
        int repl;          
        boolean inclusive; 

        int numSets;
        int blockBits;
        int indexBits;

        Line[][] sets;
        int time = 0;     
        Cache(int size, int assoc, int blockSize, int repl, boolean inclusive) {
            this.size = size;
            this.assoc = assoc;
            this.blockSize = blockSize;
            this.repl = repl;
            this.inclusive = inclusive;
            if (size == 0 || assoc == 0) {
                this.numSets = 0;
                this.blockBits = log2(blockSize);
                this.indexBits = 0;
                this.sets = new Line[0][0];
            } else {
                this.blockBits = log2(blockSize);
                this.numSets = size / (blockSize * assoc);
                this.indexBits = (numSets > 0) ? log2(numSets) : 0;

                this.sets = new Line[numSets][assoc];
                for (int i = 0; i < numSets; i++) {
                    for (int j = 0; j < assoc; j++) {
                        sets[i][j] = new Line();
                    }
                }
            }
        }
        int log2(int x) {
            int r = 0;
            while ((x >> r) > 1) r++;
            return r;
        }
        int getSetIndex(int addr) {
            if (numSets == 0) return 0;
            int blockAddr = addr >>> blockBits;
            return blockAddr & ((1 << indexBits) - 1);
        }
        int getTag(int addr) {
            return addr >>> (blockBits + indexBits);
        }
        int reconstructAddr(int set, int tag) {
            int blockAddr = (tag << indexBits) | set;
            return blockAddr << blockBits;
        }
        int findLineIndex(int set, int tag) {
            if (numSets == 0) return -1;
            for (int way = 0; way < assoc; way++) {
                Line line = sets[set][way];
                if (line.valid && line.tag == tag) {
                    return way;
                }
            }
            return -1;
        }
        int chooseVictim(int set) {
            for (int way = 0; way < assoc; way++) {
                if (!sets[set][way].valid) {
                    return way;
                }
            }
            int victim = 0;
            int minAge = sets[set][0].age;
            for (int way = 1; way < assoc; way++) {
                if (sets[set][way].age < minAge) {
                    minAge = sets[set][way].age;
                    victim = way;
                }
            }
            return victim;
        }
    }
    static Cache L1;
    static Cache L2;
    public static void main(String[] args) {
        if (args.length != 8) {
            System.err.println(
                "Usage: java sim_cache <BLOCKSIZE> <L1_SIZE> <L1_ASSOC> <L2_SIZE> <L2_ASSOC> " +
                "<REPLACEMENT_POLICY> <INCLUSION_PROPERTY> <trace_file>"
            );
            System.exit(1);
        }
        BLOCKSIZE          = Integer.parseInt(args[0]);
        L1_SIZE            = Integer.parseInt(args[1]);
        L1_ASSOC           = Integer.parseInt(args[2]);
        L2_SIZE            = Integer.parseInt(args[3]);
        L2_ASSOC           = Integer.parseInt(args[4]);
        REPLACEMENT_POLICY = Integer.parseInt(args[5]);
        INCLUSION_PROPERTY = Integer.parseInt(args[6]);
        trace_file         = args[7];
        boolean hasL2 = (L2_SIZE != 0 && L2_ASSOC != 0);
        L1 = new Cache(L1_SIZE, L1_ASSOC, BLOCKSIZE, REPLACEMENT_POLICY, false);
        L2 = new Cache(L2_SIZE, L2_ASSOC, BLOCKSIZE, REPLACEMENT_POLICY, INCLUSION_PROPERTY == 1);
        runTrace(hasL2);
        printOutput(hasL2);
    }
    static void runTrace(boolean hasL2) {
        try (BufferedReader br = new BufferedReader(new FileReader(trace_file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length != 2) continue;

                char op = parts[0].charAt(0); // 'r' or 'w'
                // parse 32-bit address
                int addr = (int) (Long.parseLong(parts[1], 16) & 0xffffffffL);

                accessL1(addr, op, hasL2);
            }
        } catch (IOException e) {
            System.err.println("Cannot open trace file: " + trace_file);
            System.exit(1);
        }
    }
    static void accessL1(int addr, char op, boolean hasL2) {
        L1.time++;
        int set = L1.getSetIndex(addr);
        int tag = L1.getTag(addr);
        int lineIndex = L1.findLineIndex(set, tag);
        if (op == 'r') {
            L1_read++;
            if (lineIndex != -1) {
                L1_hits++;
                if (REPLACEMENT_POLICY == 0) { 
                    L1.sets[set][lineIndex].age = L1.time;
                }
                return;
            }
            L1_readMiss++;
            int victimWay = L1.chooseVictim(set);
            Line victim = L1.sets[set][victimWay];
            if (victim.valid && victim.dirty) {
                L1_writebacks++;
                if (hasL2) {
                    int victimAddr = L1.reconstructAddr(set, victim.tag);
                    accessL2(victimAddr, 'w', hasL2); 
                }
            }
            if (hasL2) {
                accessL2(addr, 'r', hasL2);
            }
            victim.valid = true;
            victim.tag = tag;
            victim.dirty = false;
            victim.age = L1.time;
        } else {
            L1_writes++;
            if (lineIndex != -1) {
                L1_hits++;
                Line line = L1.sets[set][lineIndex];
                if (REPLACEMENT_POLICY == 0) {
                    line.age = L1.time;
                }
                line.dirty = true;
                return;
            }
            L1_writeMiss++;
            int victimWay = L1.chooseVictim(set);
            Line victim = L1.sets[set][victimWay];
            if (victim.valid && victim.dirty) {
                L1_writebacks++;
                if (hasL2) {
                    int victimAddr = L1.reconstructAddr(set, victim.tag);
                    accessL2(victimAddr, 'w', hasL2);
                }
            }
            if (hasL2) {
                accessL2(addr, 'r', hasL2);
            }
            victim.valid = true;
            victim.tag = tag;
            victim.dirty = true;
            victim.age = L1.time;
        }
    }
    static void accessL2(int addr, char op, boolean hasL2) {
        if (!hasL2) return;
        L2.time++;
        int set = L2.getSetIndex(addr);
        int tag = L2.getTag(addr);
        int lineIndex = L2.findLineIndex(set, tag);
        if (op == 'r') {
            L2_reads++;
            if (lineIndex != -1) {
                L2_hits++;
                if (REPLACEMENT_POLICY == 0) { 
                    L2.sets[set][lineIndex].age = L2.time;
                }
                return;
            }
            L2_readMiss++;
            int victimWay = L2.chooseVictim(set);
            Line victim = L2.sets[set][victimWay];
            if (victim.valid && victim.dirty) {
                L2_writebacks++;
            }
            if (victim.valid && L2.inclusive && L1.size != 0 && L1.assoc != 0) {
                int victimAddr = L2.reconstructAddr(set, victim.tag);
                invalidateInL1(victimAddr);
            }
            victim.valid = true;
            victim.tag = tag;
            victim.dirty = false;
            victim.age = L2.time;
        } else { 
            L2_writes++;
            if (lineIndex != -1) {
                L2_hits++;
                Line line = L2.sets[set][lineIndex];
                if (REPLACEMENT_POLICY == 0) {
                    line.age = L2.time;
                }
                line.dirty = true;
                return;
            }
            L2_writeMiss++;
            int victimWay = L2.chooseVictim(set);
            Line victim = L2.sets[set][victimWay];
            if (victim.valid && victim.dirty) {
                L2_writebacks++;
            }
            if (victim.valid && L2.inclusive && L1.size != 0 && L1.assoc != 0) {
                int victimAddr = L2.reconstructAddr(set, victim.tag);
                invalidateInL1(victimAddr);
            }
            victim.valid = true;
            victim.tag = tag;
            victim.dirty = true;
            victim.age = L2.time;
        }
    }
    static void invalidateInL1(int addr) {
        int set = L1.getSetIndex(addr);
        int tag = L1.getTag(addr);
        for (int way = 0; way < L1.assoc; way++) {
            Line line = L1.sets[set][way];
            if (line.valid && line.tag == tag) {
                if (line.dirty) {
                    inclusiveWritebackCount++;
                }
                line.valid = false;
                line.dirty = false;
                break;
            }
        }
    }
    static void printOutput(boolean hasL2) {
        System.out.println("===== Simulator configuration =====");
        System.out.println("BLOCKSIZE:             " + BLOCKSIZE);
        System.out.println("L1_SIZE:               " + L1_SIZE);
        System.out.println("L1_ASSOC:              " + L1_ASSOC);
        System.out.println("L2_SIZE:               " + L2_SIZE);
        System.out.println("L2_ASSOC:              " + L2_ASSOC);
        System.out.println("REPLACEMENT POLICY:    " + (REPLACEMENT_POLICY == 0 ? "LRU" : "FIFO"));
        System.out.println("INCLUSION PROPERTY:    " + (INCLUSION_PROPERTY == 1 ? "inclusive" : "non-inclusive"));
        System.out.println("trace_file:            " + trace_file);

        System.out.println("===== L1 contents =====");
        for (int i = 0; i < L1.numSets; i++) {
            StringBuilder sb = new StringBuilder();
            if (i <= 9) {
                sb.append("Set     ").append(i).append(":      ");
            } else if (i <= 99) {
                sb.append("Set     ").append(i).append(":     ");
            } else {
                sb.append("Set     ").append(i).append(":    ");
            }
            for (int way = 0; way < L1.assoc; way++) {
                Line line = L1.sets[i][way];
                if (!line.valid) continue;
                sb.append(Integer.toHexString(line.tag));
                if (line.dirty) sb.append(" D");
                sb.append("   ");
            }
            System.out.println(sb.toString().trim());
        }
        if (hasL2) {
            System.out.println("===== L2 contents =====");
            for (int i = 0; i < L2.numSets; i++) {
                StringBuilder sb = new StringBuilder();
                if (i <= 9) {
                    sb.append("Set     ").append(i).append(":      ");
                } else if (i <= 99) {
                    sb.append("Set     ").append(i).append(":     ");
                } else {
                    sb.append("Set     ").append(i).append(":    ");
                }
                for (int way = 0; way < L2.assoc; way++) {
                    Line line = L2.sets[i][way];
                    if (!line.valid) continue;
                    sb.append(Integer.toHexString(line.tag));
                    if (line.dirty) sb.append(" D");
                    sb.append("   ");
                }
                System.out.println(sb.toString().trim());
            }
        }
        double L1MissRate = (double) (L1_readMiss + L1_writeMiss) /
                (double) (L1_read + L1_writes);
        double L2MissRate = 0.0;
        if (hasL2 && L2_reads > 0) {
            L2MissRate = (double) L2_readMiss / (double) L2_reads;
        }
        System.out.println("===== Simulation results (raw) =====");
        System.out.println("a. number of L1 reads:        " + L1_read);
        System.out.println("b. number of L1 read misses:  " + L1_readMiss);
        System.out.println("c. number of L1 writes:       " + L1_writes);
        System.out.println("d. number of L1 write misses: " + L1_writeMiss);
        System.out.printf ("e. L1 miss rate:              %.6f%n", L1MissRate);
        System.out.println("f. number of L1 writebacks:   " + L1_writebacks);
        System.out.println("g. number of L2 reads:        " + L2_reads);
        System.out.println("h. number of L2 read misses:  " + L2_readMiss);
        System.out.println("i. number of L2 writes:       " + L2_writes);
        System.out.println("j. number of L2 write misses: " + L2_writeMiss);
        if (hasL2) {
            System.out.printf("k. L2 miss rate:              %.6f%n", L2MissRate);
        } else {
            System.out.println("k. L2 miss rate:              0");
        }
        System.out.println("l. number of L2 writebacks:   " + L2_writebacks);

        long memoryTraffic;
        if (!hasL2) {
            memoryTraffic = L1_readMiss + L1_writeMiss + L1_writebacks;
        } else {
            memoryTraffic = L2_readMiss + L2_writeMiss + L2_writebacks + inclusiveWritebackCount;
        }
        System.out.println("m. total memory traffic:      " + memoryTraffic);
    }
}
