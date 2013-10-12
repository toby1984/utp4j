package ch.uzh.csg.utp4j.channels.impl.write;

import java.io.IOException;

import ch.uzh.csg.utp4j.channels.futures.UtpWriteFuture;

public class UtpWriteFutureImpl extends UtpWriteFuture {
		
	
	public UtpWriteFutureImpl() throws InterruptedException {
		super();
	}

	public void finished(IOException exp, int bytesWritten) {
		this.setBytesSend(bytesWritten);
		this.exception = exp;
		isDone = true;
		semaphore.release();
	}

	public void setBytesSend(int position) {
		bytesWritten = position;
	}

}
