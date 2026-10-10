package org.springaicommunity.inspector.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlobStoreTest {

	@Test
	void keepsTheNewestWithinTheBudgetAndRefusesWhatDoesNotFit() {
		BlobStore store = new BlobStore(25, 10);

		String a = store.put(new byte[10], "image/png");
		String b = store.put(new byte[10], "image/png");
		String c = store.put(new byte[10], "image/png"); // 30 > 25: a goes
		assertThat(store.put(new byte[11], "image/png")).isNull(); // larger than one item may be
		assertThat(store.put(new byte[0], "image/png")).isNull();

		assertThat(store.get(a)).isNull();
		assertThat(store.get(b)).isNotNull();
		assertThat(store.get(c)).isNotNull();
		assertThat(store.size()).isEqualTo(2);

		store.clear();
		assertThat(store.get(b)).isNull();
	}

}
