import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MOD Music PCM Generator
 * 
 * References:
 * https://hornet.org/code/audio/docs/fmoddoc2.zip
 * https://github.com/Prezzodaman/pymod
 * https://github.com/NardJ/ModTrack-for-Python/tree/master/mod_refs
 * 
 * @author Leonardo Ono (ono.leo80@gmail.com)
 */
public class MOD {

    public static final int DATA_LINE_SAMPLE_RATE = 44100;

    public static final int AMIGA_CLOCK = 7159090;
    
    private static class Sample {

        final int length;
        int fineTune; // can be modified using extended effect 0x5
        final int volume;
        final int loopStart;
        final int loopLength;
        final int loopEnd;
        final boolean useLoop;
        byte[] sampleData;
        
        public Sample(int length, int fineTune, int volume, int loopStart, int loopLength) {
            this.length = length;
            this.fineTune = fineTune;
            this.volume = volume;
            this.loopStart = loopStart;
            this.loopLength = loopLength;
            this.loopEnd = loopStart + loopLength - 1;
            this.useLoop = loopLength > 2;
        }

    }

    private static class PatternNote {

        final int sampleNumber;
        final int samplePeriodValue;
        final int effectNumber;
        final int effectParameters;
    
        public PatternNote(long value) {
            sampleNumber = (int) (((value & 0xf0000000) >> 24) + ((value & 0xf000) >> 12));
            samplePeriodValue = (int) ((value & 0xfff0000) >> 16);
            effectNumber = (int) ((value & 0xf00) >> 8);
            effectParameters = (int) (value & 0xff);
        }

    }

    private static class Channel {
    
        Sample sample;
    
        double notePeriod;

        double noteFrequency;
        double pitchFactor;
    
        double lastSampleIndex;
        double sampleIndex;
        double sampleIndexInc;
        
        int volume;
        int hardwareVolume;
        double volumeFactor;
    
        // effects
        int portaSpeed;
        int noteToPortaTo;
    
        int vibratoPos; // -32 to +31
        int vibratoDepth;
        int vibratoSpeed;
        int vibratoWavControl;
    
        int tremoloPos; // -32 to +31
        int tremoloDepth;
        int tremoloSpeed;
        int tremoloWavControl;

        int loopRow;
        int loopCount;

        int nextDelayNote;
        int nextRetrigNote;

        public void setSample(Sample sample) {
            this.sample = sample;
        }
        
        public void setHardwareVolume(int volume) {
            if (volume < 0) volume = 0;
            if (volume > 64) volume = 64;
            this.hardwareVolume = volume;
            this.volumeFactor = volume / 64.0;
        }
        
        public void setNotePeriod(double period) {
            this.notePeriod = period;
            this.noteFrequency = AMIGA_CLOCK / (2.0 * period);
            int fineTune = 0;
            if (sample != null) {
                fineTune = sample.fineTune;
            }
            noteFrequency = noteFrequency * Math.pow(2.0, (1.0 / 12.0 / 8.0) * fineTune);
        }
        
        public void setHardwareFrequency(double noteFrequency) {
            this.pitchFactor = noteFrequency / DATA_LINE_SAMPLE_RATE;
            this.sampleIndexInc = pitchFactor;
        }
        
        public byte getNextSample() {
            byte nextSample = 0;
    
            if (sample == null) {
                return nextSample;
            }
            
            int sampleIndexInt = (int) sampleIndex;
    
            if (sample.useLoop) {
                // before loop
                int loopEnd = Math.min(sample.loopEnd, sample.sampleData.length);
                if (sampleIndexInt <= loopEnd) {
                    nextSample = sample.sampleData[sampleIndexInt];
                }
                // after loop
                else {
                    nextSample = sample.sampleData[sample.loopStart 
                        + ((sampleIndexInt - sample.loopEnd) % sample.loopLength)];
                }
            }
            else if (sampleIndexInt < sample.sampleData.length) {
                nextSample = sample.sampleData[sampleIndexInt];
            }
    
            sampleIndex += sampleIndexInc;
            return (byte) (nextSample * volumeFactor);
        }
        
        public void triggerNote(MOD mod, PatternNote note) {
            if (note.sampleNumber > 0) {
                Sample sample = mod.samples[note.sampleNumber - 1];
                setSample(sample);
                setHardwareVolume(sample.volume);
                volume = sample.volume;
                sampleIndex = 0; 
            }
            
            if (note.samplePeriodValue > 0) {
                if (vibratoWavControl < 4) {
                    vibratoPos = 0;
                } 
    
                if (tremoloWavControl < 4) {
                    tremoloPos = 0;
                } 
    
                if (note.effectNumber != 3 && note.effectNumber != 5) {
                    setNotePeriod(note.samplePeriodValue);
                    setHardwareFrequency(noteFrequency);
                }
            }    
        }

        // tick 0
        public void startEffect(PatternNote note) {
            switch (note.effectNumber) {
                case 0x3 -> { // porta to note (glissando)
                    if (note.effectParameters > 0) {
                        portaSpeed = note.effectParameters;
                    }
                    if (note.samplePeriodValue > 0) {
                        noteToPortaTo = note.samplePeriodValue;
                    }
                }
    
                case 0x4 -> { // vibrato
                    int vibratoSpeed = (note.effectParameters & 0xf0) >> 4;
                    if (vibratoSpeed > 0) {
                        this.vibratoSpeed = vibratoSpeed;
                    }
                    int vibratoDepth = note.effectParameters & 0xf;
                    if (vibratoDepth > 0) {
                        this.vibratoDepth = vibratoDepth;
                    }
                }
    
                case 0x7 -> { // tremolo
                    int tremoloSpeed = (note.effectParameters & 0xf0) >> 4;
                    if (tremoloSpeed > 0) {
                        this.tremoloSpeed = tremoloSpeed;
                    }
                    int tremoloDepth = note.effectParameters & 0xf;
                    if (tremoloDepth > 0) {
                        this.tremoloDepth = tremoloDepth;
                    }
                }
    
                case 0x9 -> { // sample offset
                    if (note.effectParameters != 0) {
                        sampleIndex = note.effectParameters << 8;
                        lastSampleIndex = sampleIndex;
                    }
                    else {
                        sampleIndex = lastSampleIndex;
                    }
                }
                
                case 0x8 -> { // pan
                    // not implemented since this version does not support stereo, only mono sound
                }

                case 0xc -> { // set volume
                    setHardwareVolume(note.effectParameters);
                    volume = note.effectParameters;
                }
    
                case 0xe -> { // extended effects
                    int extendedEffectId = (note.effectParameters & 0xf0) >> 4;
                    int extendedValue = note.effectParameters & 0xf;
                    switch (extendedEffectId) {
                        case 0x0 -> { // set filter
                            // This effect turns on or off the hardware filter (not applicable to most pc sound cards)
                        } 

                        case 0x1 -> { // fine portamento up
                            notePeriod -= extendedValue;
                            if (notePeriod < 108) {
                                notePeriod = 108;
                            }
                            setNotePeriod(notePeriod);
                            setHardwareFrequency(noteFrequency);
                        }
    
                        case 0x2 -> { // fine portamento down    
                            notePeriod += extendedValue;
                            if (notePeriod > 907) {
                                notePeriod = 907;
                            }
                            setNotePeriod(notePeriod);
                            setHardwareFrequency(noteFrequency);
                        }
                        
                        case 0x3 -> { // glissando control
                            // TODO: It toggles whether to do a smooth slide or whether to slide in jumps of semitones. 
                        }

                        case 0x4 -> { // set vibrato waveform
                            vibratoWavControl = extendedValue;
                        } 
    
                        case 0x5 -> { // set instrument finetune
                            sample.fineTune = extendedValue > 7 ? extendedValue - 16 : extendedValue;
                        }
    
                        case 0x7 -> { // set tremolo waveform
                            tremoloWavControl = extendedValue;
                        }
                        
                        case 0x8 -> { // 16 position panning
                            // not implemented since this version does not support stereo, only mono sound
                        }

                        case 0x9 -> { // retrig note
                            nextRetrigNote = extendedValue;
                        }

                        case 0xa -> { // fine volume slide up
                            int volumeSlide = extendedValue;
                            volume = hardwareVolume + volumeSlide;
                            if (volume > 64) volume = 64;
                            setHardwareVolume(volume);
                        }
    
                        case 0xb -> { // fine volume slide down
                            int volumeSlide = extendedValue;
                            volume = hardwareVolume - volumeSlide;
                            if (volume < 0) volume = 0;
                            setHardwareVolume(volume);
                        }

                        case 0xd -> { // delay note
                            nextDelayNote = extendedValue;
                        }
                    }
                }
            }
        }
    
        public void updateEffect(int tick, PatternNote note) {
            switch (note.effectNumber) {
                case 0x0 -> { // arpeggio
                    switch ((tick - 1) % 3) {
                        case 0 -> setHardwareFrequency(noteFrequency);
    
                        case 1 -> {
                            int n = (note.effectParameters & 0xf0) >> 4;
                            setHardwareFrequency(noteFrequency * Math.pow(2, n / 12.0));
                        }
    
                        case 2 -> {
                            int n = (note.effectParameters & 0xf);
                            setHardwareFrequency(noteFrequency * Math.pow(2, n / 12.0));
                        }
                    }
                }
    
                case 0x1 -> { // slide up (portamento up)
                    notePeriod -= note.effectParameters;
                    if (notePeriod < 108) {
                        notePeriod = 108;
                    }
                    setNotePeriod(notePeriod);
                    setHardwareFrequency(noteFrequency);
                }
                
                case 0x2 -> { // slide down (portamento down)
                    notePeriod += note.effectParameters;
                    if (notePeriod > 907) {
                        notePeriod = 907;
                    }
                    setNotePeriod(notePeriod);
                    setHardwareFrequency(noteFrequency);
                }
    
                case 0x3 -> { // porta to note (glissando)
                    applyPortaToNoteEffect();
                }
    
                case 0x4 -> { // vibrato
                    applyVibratoEffect();
                }

                case 0x5 -> { // porta + volume slide
                    applyPortaToNoteEffect();
                    applyVolumeSlideEffect(note);
                }

                case 0x6 -> { // vibrato + volume slide
                    applyVibratoEffect();
                    applyVolumeSlideEffect(note);
                }
    
                case 0x7 -> { // tremolo
                    tremoloPos += tremoloSpeed;
                    int n = (int) (tremoloDepth * 4.0 * getWave(tremoloPos, tremoloWavControl));
                    setHardwareVolume(volume + n);
                }
    
                case 0xa -> { // volume slide
                    applyVolumeSlideEffect(note);
                }
    
                case 0xe -> { // extended effects
                    int extendedEffectId = (note.effectParameters & 0xf0) >> 4;
                    int extendedValue = note.effectParameters & 0xf;
                    switch (extendedEffectId) {
                        case 0xc -> { // cut note
                            if (tick == extendedValue) {
                                volume = 0;
                                setHardwareVolume(volume);
                            }
                        }
                    }
                }

            } 
        }
        
        private double getWave(double wavPos, int wavControl) {
            double y = 0;
            double x = (wavPos / 64.0) * 2 * Math.PI;
            int waveform = wavControl & 0x7;
            switch (waveform) {
                case 0, 3 -> y = Math.sin(x); // sine and random
                case 1 -> y = ((x % (2 * Math.PI)) / Math.PI) - 1.0;  // ramp down
                case 2 -> y = Math.sin(x) < 0 ? 1.0 : -1.0;  // square
            }
            return y;
        }

        private void applyPortaToNoteEffect() {
            double sign = noteToPortaTo - notePeriod;
            if (sign > 0) {
                notePeriod += portaSpeed;
                if (notePeriod > noteToPortaTo) {
                    notePeriod = noteToPortaTo;
                }
            }
            else if (sign < 0) {
                notePeriod -= portaSpeed;
                if (notePeriod < noteToPortaTo) {
                    notePeriod = noteToPortaTo;
                }
            }
            setNotePeriod(notePeriod);
            setHardwareFrequency(noteFrequency);
        }

        private void applyVibratoEffect() {
            vibratoPos += vibratoSpeed;
            int n = (int) (vibratoDepth * 2.0 * getWave(vibratoPos, vibratoWavControl));
            setHardwareFrequency(noteFrequency * Math.pow(2.0, (1.0 / 12.0 / 16.0) * n));
        }

        private void applyVolumeSlideEffect(PatternNote note) {
            int volumeSlide = 0;
            int up = (note.effectParameters & 0xf0) >> 8;
            int down = (note.effectParameters & 0xf);
            if (up > 0) volumeSlide = up;
            if (down > 0) volumeSlide = -down;
            if (up > 0 && down > 0) volumeSlide = 0;
            setHardwareVolume(hardwareVolume + volumeSlide);
            volume = hardwareVolume;
        }

    }
    
    private int channelsNum = 0;
    private int songLength;
    private int samplesCount;
    private Sample[] samples;
    private int[] patternOrderTable = new int[128];
    private PatternNote[][][] notes; // [pattern][row][channel]
    private int BPM = 125; // 50hz, or 50 ticks per second for 125 BPM. Formula: HZ = (2 * BPM) / 5
    private double ticksPerSecond = (2 * BPM) / 5.0;
    private int samplesPerTick = (int) (DATA_LINE_SAMPLE_RATE * (1.0 / ticksPerSecond));
    private int speed = 6; // default speed = 6 ticks per row
    private Channel[] channels;

    public MOD(String filename, int channelsNum, int samplesCount) {
        this.channelsNum = channelsNum;
        this.samplesCount = samplesCount;
        loadMOD(filename);
        channels = new Channel[channelsNum];
        for (int i = 0; i < channelsNum; i++) {
            channels[i] = new Channel();
        }
    }

    private void loadMOD(String filename) {
        ByteBuffer bb = null;
        try (InputStream is = getClass().getResourceAsStream(filename)) {
            bb = ByteBuffer.wrap(is.readAllBytes());
            bb.order(ByteOrder.BIG_ENDIAN);
        }
        catch (Exception e) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, "can't load mod file !", e);
            System.exit(-1);
        }

        // extract samples info
        samples = new Sample[samplesCount];
        bb.position(20);
        for (int i = 0; i < samplesCount; i++) {
            bb.position(bb.position() + 22); // skip name
            int sampleLength = 2 * (bb.getShort() & 0xffff);
            int sampleFineTune = bb.get() & 0xff;
            sampleFineTune = sampleFineTune > 7 ? sampleFineTune - 16 : sampleFineTune;
            int sampleVolume = bb.get() & 0xff;
            int sampleLoopStart = 2 * (bb.getShort() & 0xffff);
            int sampleLoopLength = 2 * (bb.getShort() & 0xffff);
            Sample sample = new Sample(sampleLength, sampleFineTune, sampleVolume, sampleLoopStart, sampleLoopLength);
            samples[i] = sample;
        }

        songLength = bb.get() & 0xff;
        bb.get(); // unused byte

        int patternsCount = 0;
        for (int i = 0; i < 128; i++) {
            patternOrderTable[i] = bb.get() & 0xff;
            if (patternOrderTable[i] > patternsCount) {
                patternsCount = patternOrderTable[i];
            }
        }

        // discard 4 bytes (signature, offset 1080)
        bb.getInt(); // "M.K.", etc
        
        // extract all patterns notes
        notes = new PatternNote[++patternsCount][64][channelsNum];
        for (int p = 0; p < patternsCount; p++) {
            for (int r = 0; r < 64; r++) {
                for (int n = 0; n < channelsNum; n++) {
                    notes[p][r][n] = new PatternNote((long) (bb.getInt() & 0xffffffff));
                }
            }
        }

        // extract samples
        for (int i = 0; i < samplesCount; i++) {
            Sample sample = samples[i];
            sample.sampleData = new byte[sample.length];
            bb.get(sample.sampleData);
        }
    }

    private void startEffect(Channel channel, PatternNote note, int orderTableIndex, int currentRow) {
        switch (note.effectNumber) {
            case 0xb -> { // jump to pattern
                lastJumpToPatternRow = currentRow;
                startRow = 0;
                startPattern = note.effectParameters;
                if (startPattern < 0) startPattern = 0;
                
                // TODO: can't loop from last to first pattern.
                //       since this is not streaming, it will get stuck when generating pcm
                //if (startPattern > songLength - 1) startPattern = 0;

                // TODO: how to force exit from infinite loop properly?
                if (orderTableIndex == songLength - 1 && startPattern == 0) startPattern = songLength;

                breakPattern = true;
            }

            case 0xd -> { // pattern break
                startRow = 10 * ((note.effectParameters & 0xf0) >> 4) + (note.effectParameters & 0xf);
                if (startRow < 0) startRow = 0;
                if (startRow > 63) startRow = 0;

                // ref: FMODDOC.TXT - 5.12 Effect Bxy (Jump To Pattern)
                // You should set a flag to say there has been a pattern jump, so if there
                // is a pattern break on the same row, the pattern break effect will not
                // increment the order.  I know its strange but it is a protracker feature.
                if (currentRow != lastJumpToPatternRow) startPattern = orderTableIndex + 1;
                
                // TODO: can't loop from last to first pattern.
                //       since this is not streaming, it will get stuck when generating pcm
                //if (startPattern > songLength - 1) startPattern = 0;

                breakPattern = true;
            }

            case 0xe -> { // extended effects
                int extendedEffectId = (note.effectParameters & 0xf0) >> 4;
                int extendedValue = note.effectParameters & 0xf;
                switch (extendedEffectId) {
                    case 0x6 -> { // pattern loop
                        if (extendedValue == 0) {
                            channel.loopRow = currentRow;
                        }
                        else {
                            channel.loopCount = (channel.loopCount == 0) ? extendedValue : channel.loopCount - 1;
                            if (channel.loopCount > 0) {
                                startRow = channel.loopRow;
                                startPattern = orderTableIndex;
                                breakPattern = true;
                            }
                        }
                    }

                    case 0xe -> { // pattern delay
                        nextPatternDelay = extendedValue;
                    }

                    case 0xf -> { // invert loop
                        // This effect is not supported in any player or tracker.  Don't bother with it.
                    }
                }
            }

            case 0xf -> { // set speed / BPM
                if (note.effectParameters < 32) { // set speed (ticks per row)
                    speed = note.effectParameters;
                }
                else { // set BPM
                    BPM = note.effectParameters;
                    ticksPerSecond = (2 * BPM) / 5.0;
                    samplesPerTick = (int) (DATA_LINE_SAMPLE_RATE * (1.0 / ticksPerSecond));
                }
            }
        }        
    }

    private boolean breakPattern = false;
    private int startPattern = 0;
    private int startRow = 0;
    private int lastJumpToPatternRow = -1;
    private int nextPatternDelay = 0;

    public byte[] generatePCM() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        breakPattern = false;
        startPattern = 0;
        startRow = 0;
        lastJumpToPatternRow = -1;
        nextPatternDelay = 0;
        
        for (int ch = 0; ch < channelsNum; ch++) {
            channels[ch].loopRow = 0;
            channels[ch].loopCount = 0;
        }

        boolean playing = true;

        pattern_loop:
        while (playing) {
            for (int orderTableIndex = startPattern; orderTableIndex < songLength; orderTableIndex++) {
                if (!breakPattern) startPattern = 0;
                int patternIndex = patternOrderTable[orderTableIndex];

                for (int currentRow = startRow; currentRow < 64; currentRow++) {
                    if (breakPattern) {
                        breakPattern = false;
                        continue pattern_loop;
                    }
                    else {
                        startRow = 0;
                    }

                    int currentPatternDelay = (nextPatternDelay <= 0) ? 1 : nextPatternDelay;
                    nextPatternDelay = 0;

                    for (int ch = 0; ch < channelsNum; ch++) {
                        channels[ch].nextDelayNote = 0;
                        channels[ch].nextRetrigNote = 0;
                    }

                    while (currentPatternDelay > 0) {
                        currentPatternDelay--;

                        for (int tick = 0; tick < speed; tick++) {
                            for (int ch = 0; ch < channelsNum; ch++) {
                                Channel channel = channels[ch];
                                PatternNote note = notes[patternIndex][currentRow][ch];
                                boolean retrigNote = channel.nextRetrigNote > 0 && (tick % channel.nextRetrigNote) == 0;

                                if (tick == channel.nextDelayNote || retrigNote) {
                                    if (currentPatternDelay == 0) {
                                        
                                        // Effect EDx (Delay Note) - This effect is ignored on tick 0, 
                                        // AND you must make sure you don't play the sample on tick 0.
                                        boolean isDelayNoteEffect = note.effectNumber == 14 && (note.effectParameters & 0xf0) == 0xd0;
                                        if (!(isDelayNoteEffect && tick == 0)) {
                                            channel.triggerNote(this, note);
                                        }
        
                                        if (note.effectNumber != 0 || note.effectParameters != 0) {
                                            channel.startEffect(note);
                                            startEffect(channel, note, orderTableIndex, currentRow);
                                        }
                                    }
                                }
                                else {
                                    channel.updateEffect(tick, note);
                                }
                            }
    
                            // mix all channels
                            for (int s = 0; s < samplesPerTick; s++) {
                                byte mixedSample = 0;
                                for (int ch = 0; ch < channelsNum; ch++) {
                                    int ms = mixedSample + channels[ch].getNextSample() / 2;
                                    mixedSample = (byte) Math.max(Math.min(ms, 127), -128);
                                }
                                baos.write(mixedSample);
                            }
                        }
                    }
                }
            }
            playing = false;
        }
        
        return baos.toByteArray();
    }

}
