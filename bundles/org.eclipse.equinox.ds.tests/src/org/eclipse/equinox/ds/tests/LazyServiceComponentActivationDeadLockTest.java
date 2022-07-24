/*******************************************************************************
 * Copyright (c) 2022, 2022 Hannes Wellmann and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Hannes Wellmann - initial API and implementation
 *******************************************************************************/

package org.eclipse.equinox.ds.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

public class LazyServiceComponentActivationDeadLockTest {

	@Test
	public void testServiceFactoryDeadLock() throws Exception {

		class SimpleServiceFactory<T> implements ServiceFactory<T> {
			private final BiFunction<Bundle, ServiceRegistration<T>, T> factory;

			SimpleServiceFactory(BiFunction<Bundle, ServiceRegistration<T>, T> factory) {
				this.factory = factory;
			}

			@Override
			public T getService(Bundle bundle, ServiceRegistration<T> registration) {
				return factory.apply(bundle, registration);
			}

			@Override
			public void ungetService(Bundle bundle, ServiceRegistration<T> registration, T service) {
			}

		}

		class Service1 {
		}

		class Service2 {
		}

		CountDownLatch l1 = new CountDownLatch(1);
		CountDownLatch l2 = new CountDownLatch(1);
		BundleContext ctx = DSTestsActivator.getContext();

		ServiceFactory<Service1> factory1 = new SimpleServiceFactory<>((bundle, registration) -> {
			countDownAndAwaitOther(l1, l2);
			ctx.getService(ctx.getServiceReference(Service2.class));
			return new Service1();
		});
		ServiceFactory<Service2> factory2 = new SimpleServiceFactory<>((bundle, registration) -> {
			countDownAndAwaitOther(l2, l1);
			ctx.getService(ctx.getServiceReference(Service1.class));
			return new Service2();
		});
		ctx.registerService(Service1.class, factory1, null);
		ctx.registerService(Service2.class, factory2, null);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Service1> service1 = executor.submit(() -> ctx.getService(ctx.getServiceReference(Service1.class)));
			Future<Service2> service2 = executor.submit(() -> ctx.getService(ctx.getServiceReference(Service2.class)));
			Service1 s1 = service1.get(5, TimeUnit.SECONDS); // times out in case of dead-lock
			Service2 s2 = service2.get(5, TimeUnit.SECONDS); // times out in case of dead-lock
			if (s1 != null && s2 != null) {
				fail("At least one dead-locked component should be null");
			}
			// TODO: when we have a strategy to re-cover from the deadlock, check that both
			// services are not null.
		} finally {
			executor.shutdown();
		}
	}

	void countDownAndAwaitOther(CountDownLatch l1, CountDownLatch l2) {
		l1.countDown();
		try {
			l2.await();
		} catch (InterruptedException e) {
		}
	}

	@Test
	public void testLateBindingInSameBundleDeadLock() throws Exception {
		BundleContext ctx = DSTestsActivator.getContext();
		ServiceReference<Component1> reference = ctx.getServiceReference(Component1.class);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Component1> service = executor.submit(() -> ctx.getService(reference));
			Component1 c1 = service.get(5, TimeUnit.SECONDS); // times out in case of dead-lock
			// component might or might not be null depending on in which thread the
			// DeadLock was detected and the SCR-ServiceFactory therefore threw an
			// Exception.
			if (c1 != null) {
				// If the main-thread did not detect the dead-lock and therefore Component1 is
				// successfully created, the multi-ref from its Comp2 to Comp3 must be empty and
				// its Comp4 must have its Comp5 successfully created.
				assertTrue(c1.a.c3s.isEmpty());
				assertNotNull(c1.b.b);
			}
			// TODO: when we have a strategy to re-cover from the deadlock, check that c1 is
			// never null and that c1.a.c3s is not empty and its only element has a Comp2
			// and Comp5
		} finally {
			executor.shutdown();
			// ctx.ungetService(reference);
		}
	}

	@Component(service = Component1.class)
	public static class Component1 {
		@Reference
		Component2 a; // activate first
		@Reference
		Component4 b; // activate second
	}

	@Component(service = Component2.class)
	public static class Component2 {

		@Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
		final List<Component3> c3s = new CopyOnWriteArrayList<>();
	}

	@Component(service = Component3.class)
	public static class Component3 {

		@Reference
		Component2 a;

		@Reference
		Component5 b;
	}

	@Component(service = Component4.class)
	public static class Component4 {

		@Reference
		Component5 b;

		@Reference
		AwaitComponent5Activation a; // ensure this reference is activated first

		// The nested component ensures that the activation of this component's
		// reference to Component5 starts only after the activation of Component5 has
		// started in the SCR-Actor thread.

		@Component(service = AwaitComponent5Activation.class)
		public static class AwaitComponent5Activation {
			@Activate
			public void activated() throws InterruptedException {
				Component5.ACTIVATION_STARTED.await();
			}
		}
	}

	@Component(service = Component5.class)
	public static class Component5 {

		@Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
		final List<Component1> c1s = new ArrayList<>();

		@Reference
		ActivationStartedIndicator a; // activate first

		static final CountDownLatch ACTIVATION_STARTED = new CountDownLatch(1);
		// Count-down is another component to let it happen when this component has
		// started its activation but did not completed it.

		@Component(service = ActivationStartedIndicator.class)
		public static class ActivationStartedIndicator {

			@Activate
			public void activated() {
				ACTIVATION_STARTED.countDown();
			}
		}
	}

}
