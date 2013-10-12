package ch.uzh.csg.utp4j.channels.impl.read;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.uzh.csg.utp4j.channels.UtpSocketState;
import ch.uzh.csg.utp4j.channels.impl.UtpSocketChannelImpl;
import ch.uzh.csg.utp4j.channels.impl.UtpTimestampedPacketDTO;
import ch.uzh.csg.utp4j.channels.impl.alg.UtpAlgConfiguration;
import ch.uzh.csg.utp4j.channels.impl.alg.UtpAlgorithm;
import ch.uzh.csg.utp4j.data.MicroSecondsTimeStamp;
import ch.uzh.csg.utp4j.data.SelectiveAckHeaderExtension;
import ch.uzh.csg.utp4j.data.UtpPacket;
import ch.uzh.csg.utp4j.data.UtpPacketUtils;
import ch.uzh.csg.utp4j.data.bytes.UnsignedTypesUtil;

public class UtpReadingRunnable extends Thread implements Runnable {
	
	private static final int PACKET_DIFF_WARP = 50000;
	private IOException exp;
	private ByteBuffer buffer;
	private UtpSocketChannelImpl channel;
	private SkippedPacketBuffer skippedBuffer = new SkippedPacketBuffer();
	private boolean exceptionOccured = false;
	private boolean graceFullInterrupt;
	private boolean isRunning;
	private MicroSecondsTimeStamp timeStamper;
	private long totalPayloadLength = 0;
	private long gotLastPacketTimeStamp;
	private int lastPayloadLength;
	private UtpReadFutureImpl readFuture;
	private long nowtimeStamp;
	private long lastPackedRecieved;
	private long startReadingTimeStamp;
	private boolean gotLastPacket = false;
	
	private static final Logger log = LoggerFactory.getLogger(UtpReadingRunnable.class);
	
	public UtpReadingRunnable(UtpSocketChannelImpl channel, ByteBuffer buff, MicroSecondsTimeStamp timestamp, UtpReadFutureImpl future) {
		this.channel = channel;
		this.buffer = buff;
		this.timeStamper = timestamp;
		this.readFuture = future;
		lastPayloadLength = UtpAlgConfiguration.MAX_PACKET_SIZE;
		this.startReadingTimeStamp = timestamp.timeStamp();
	}
	
	public int getBytesRead() {
		return 0;
	}

	public IOException getException() {
		return exp;
	}

	public boolean hasExceptionOccured() {
		return false;
	}

	@Override
	public void run() {
//		buffer.flip();

		isRunning = true;
		IOException exp = null;
		while(continueReading()) {
			BlockingQueue<UtpTimestampedPacketDTO> queue = channel.getDataGramQueue();
			try {
				UtpTimestampedPacketDTO timestampedPair = queue.poll(UtpAlgConfiguration.TIME_WAIT_AFTER_LAST_PACKET/2, TimeUnit.MICROSECONDS);
				nowtimeStamp = timeStamper.timeStamp();
				if (timestampedPair != null) {
					lastPackedRecieved = timestampedPair.stamp();
					if (isLastPacket(timestampedPair)) {
						gotLastPacket  = true;
						log.debug("GOT LAST PACKET");
						gotLastPacketTimeStamp = timeStamper.timeStamp();
					}
					if (isPacketExpected(timestampedPair.utpPacket())) {
						handleExpectedPacket(timestampedPair);								
					} else {
						handleUnexpectedPacket(timestampedPair);
					}												
				} 
				/*TODO: HOWTOMEASURE RTT HERE?*/
				if (isTimedOut()) {
					log.debug("now: " + nowtimeStamp + " last: " + lastPackedRecieved + " = " + (nowtimeStamp - lastPackedRecieved));
					log.debug("now: " + nowtimeStamp + " start: " + startReadingTimeStamp + " = " + (nowtimeStamp - startReadingTimeStamp));

					throw new IOException();
				}
					
			} catch (IOException ioe) {
				exp = ioe;
				exp.printStackTrace();
				exceptionOccured = true;
			} catch (InterruptedException iexp) {
				iexp.printStackTrace();
				exceptionOccured = true;
			} catch (ArrayIndexOutOfBoundsException aexp) {
				aexp.printStackTrace();
				exceptionOccured = true;
				exp = new IOException();
			}
		}
		isRunning = false;
		readFuture.finished(exp, buffer);
		
		
		log.debug("Buffer position: " + buffer.position() + " buffer limit: " + buffer.limit());
		log.debug("PAYLOAD LENGHT " + totalPayloadLength);
		log.debug("READER OUT");

		channel.removeReader();

	}
	

	private boolean isTimedOut() {
		boolean timedOut = nowtimeStamp - lastPackedRecieved >= 5000000;
		boolean connectionReattemptAwaited = nowtimeStamp - startReadingTimeStamp >= 10000000;
		return timedOut && connectionReattemptAwaited;
	}

	private boolean isLastPacket(UtpTimestampedPacketDTO timestampedPair) {
		return (timestampedPair.utpPacket().getWindowSize() & 0xFFFFFFFF) == 0;
	}

	private void handleExpectedPacket(UtpTimestampedPacketDTO timestampedPair) throws IOException {
		if (hasSkippedPackets()) {
			buffer.put(timestampedPair.utpPacket().getPayload());
			int payloadLength = timestampedPair.utpPacket().getPayload().length;
			lastPayloadLength = payloadLength;
			totalPayloadLength += payloadLength;
			Queue<UtpTimestampedPacketDTO> packets = skippedBuffer.getAllUntillNextMissing();
			int lastSeqNumber = 0;
			if (packets.isEmpty()) {
				lastSeqNumber = timestampedPair.utpPacket().getSequenceNumber() & 0xFFFF;
			}
			UtpPacket lastPacket = null;
			for (UtpTimestampedPacketDTO p : packets) {
				buffer.put(p.utpPacket().getPayload());
				payloadLength += p.utpPacket().getPayload().length;
				lastSeqNumber = p.utpPacket().getSequenceNumber() & 0xFFFF;
				lastPacket = p.utpPacket();
			}
			skippedBuffer.reindex(lastSeqNumber);
			channel.setAckNumber(lastSeqNumber);
			//if still has skipped packets, need to selectively ack
			if (hasSkippedPackets()) {
				SelectiveAckHeaderExtension headerExtension = skippedBuffer.createHeaderExtension();
				channel.selectiveAckPacket(timestampedPair.utpPacket(), headerExtension, getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());			

			} else {
				channel.ackPacket(lastPacket, getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());
			}
		} else {
			channel.ackPacket(timestampedPair.utpPacket(), getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());
			buffer.put(timestampedPair.utpPacket().getPayload());
			totalPayloadLength += timestampedPair.utpPacket().getPayload().length;
		}
	}
	
	private long getLeftSpaceInBuffer() throws IOException {
		return (skippedBuffer.getFreeSize()) * lastPayloadLength;
	}

	private int getTimestampDifference(UtpTimestampedPacketDTO timestampedPair) {
		int difference = timeStamper.utpDifference(timestampedPair.utpTimeStamp(), timestampedPair.utpPacket().getTimestamp());
		return difference;
	}

	private void handleUnexpectedPacket(UtpTimestampedPacketDTO timestampedPair) throws IOException {
		int expected = getExpectedSeqNr();
		int seqNr = timestampedPair.utpPacket().getSequenceNumber() & 0xFFFF;
		if (skippedBuffer.isEmpty()) {
			skippedBuffer.setExpectedSequenceNumber(expected);
		}
		boolean alreadyAcked = expected > seqNr || seqNr - expected > PACKET_DIFF_WARP;
		
		boolean saneSeqNr = expected == skippedBuffer.getExpectedSequenceNumber();
		//TODO: wrapping seq nr: expected can be 5 e.g.
		// but buffer can recieve 65xxx, which already has been acked, since seq numbers wrapped. 
		// current implementation puts this wrongly into the buffer. it should go in the else block
		// possible fix: alreadyAcked = expected > seqNr || seqNr - expected > CONSTANT;
		if (saneSeqNr && !alreadyAcked) {
			skippedBuffer.bufferPacket(timestampedPair);
			SelectiveAckHeaderExtension headerExtension = skippedBuffer.createHeaderExtension();
			channel.selectiveAckPacket(timestampedPair.utpPacket(), headerExtension, getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());	
		} else {
			channel.ackAlreadyAcked(timestampedPair.utpPacket(), getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());
		}
	}
	
	public boolean isPacketExpected(UtpPacket utpPacket) {
		int seqNumberFromPacket = utpPacket.getSequenceNumber() & 0xFFFF;
		return getExpectedSeqNr() == seqNumberFromPacket;
	}
	
	private int getExpectedSeqNr() {
		int ackNumber = channel.getAckNumber();
		if (ackNumber == UnsignedTypesUtil.MAX_USHORT) {
			return 1;
		} 
		return ackNumber + 1;
	}

	private boolean hasSkippedPackets() {
		return !skippedBuffer.isEmpty();
	}

	public void graceFullInterrupt() {
		this.graceFullInterrupt = true;
	}

	private boolean continueReading() {
		return !graceFullInterrupt && !exceptionOccured 
				&& (!gotLastPacket || hasSkippedPackets() || !timeAwaitedAfterLastPacket());
	}
		
	private boolean timeAwaitedAfterLastPacket() {
		return (timeStamper.timeStamp() - gotLastPacketTimeStamp) > UtpAlgConfiguration.TIME_WAIT_AFTER_LAST_PACKET 
				&& gotLastPacket;
	}

	public boolean isRunning() {
		return isRunning;
	}

	public MicroSecondsTimeStamp getTimestamp() {
		return timeStamper;
	}

	public void setTimestamp(MicroSecondsTimeStamp timestamp) {
		this.timeStamper = timestamp;
	}

}
