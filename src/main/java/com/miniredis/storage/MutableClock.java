package com.miniredis.storage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

public class MutableClock extends Clock {
	private final AtomicLong currentMillis;
	
	public MutableClock(long initialMillis) {
		this.currentMillis = new AtomicLong(initialMillis);
	}
	
	public void advanceMillis(long millis) {
		currentMillis.addAndGet(millis);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.fixed(instant(), zone);
	}

	@Override
	public Instant instant() {
		// TODO Auto-generated method stub
		return Instant.ofEpochMilli(millis());
	}
	
	@Override
	public long millis() {
		return currentMillis.get();
	}
}
