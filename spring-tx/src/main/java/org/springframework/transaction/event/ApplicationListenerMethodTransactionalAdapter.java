/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.transaction.event;

import java.lang.reflect.Method;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link GenericApplicationListener} adapter that delegates the processing of
 * an event to a {@link TransactionalEventListener} annotated method. Supports
 * the exact same features as any regular {@link EventListener} annotated method
 * but is aware of the transactional context of the event publisher.
 *
 * <p>Processing of {@link TransactionalEventListener} is enabled automatically
 * when Spring's transaction management is enabled. For other cases, registering
 * a bean of type {@link TransactionalEventListenerFactory} is required.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @since 4.2
 * @see ApplicationListenerMethodAdapter
 * @see TransactionalEventListener
 * 执行的大致流程
 * TransactionInterceptor -> TransactionManager.commit -> AbstractPlatformTransactionManager.processCommit
 * -> TransactionSynchronizationUtils.trigger* -> TransactionSynchronizationManager.getSynchronizations
 * -> TransactionSynchronization.* -> 各种 TransactionSynchronization 实现类的执行
 *
 * 依据上面这种规范化的控制流程，我们想要在事务的提交前后执行就很容易的，只要注册 TransactionSynchronization 就行
 */
class ApplicationListenerMethodTransactionalAdapter extends ApplicationListenerMethodAdapter {

	private final TransactionalEventListener annotation;


	public ApplicationListenerMethodTransactionalAdapter(String beanName, Class<?> targetClass, Method method) {
		super(beanName, targetClass, method);
		TransactionalEventListener ann = AnnotatedElementUtils.findMergedAnnotation(method, TransactionalEventListener.class);
		if (ann == null) {
			throw new IllegalStateException("No TransactionalEventListener annotation found on method: " + method);
		}
		this.annotation = ann;
	}


	/**
	 * 这里实现了在事务的哪个位置执行监听器里面的逻辑
	 * 通过 ThreadLocal 暂存这些 Listener，待到一定的时机去执行
	 * @param event
	 */
	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (TransactionSynchronizationManager.isSynchronizationActive()
				&& TransactionSynchronizationManager.isActualTransactionActive()) {
			// 这里将事件，监听器，以及执行阶段封装起来
			TransactionSynchronization transactionSynchronization = createTransactionSynchronization(event);
			// 注册到事件管理平台，在事件推进的过程中，会执行一些钩子方法，从而执行这里 listener
			// 是不是可以自定义一个 @CustomerEventListener
			// 这里也有 ThreadLocal 的巧妙用法，比如说在事件之间传递与统计状态
			TransactionSynchronizationManager.registerSynchronization(transactionSynchronization);
		}
		else if (this.annotation.fallbackExecution()) {
			if (this.annotation.phase() == TransactionPhase.AFTER_ROLLBACK && logger.isWarnEnabled()) {
				logger.warn("Processing " + event + " as a fallback execution on AFTER_ROLLBACK phase");
			}
			// 事务回滚也能够进行处理
			processEvent(event);
		}
		else {
			// No transactional event execution at all
			if (logger.isDebugEnabled()) {
				logger.debug("No transaction is active - skipping " + event);
			}
		}
	}

	private TransactionSynchronization createTransactionSynchronization(ApplicationEvent event) {
		return new TransactionSynchronizationEventAdapter(this, event, this.annotation.phase());
	}


	private static class TransactionSynchronizationEventAdapter extends TransactionSynchronizationAdapter {

		private final ApplicationListenerMethodAdapter listener;

		private final ApplicationEvent event;

		private final TransactionPhase phase;

		public TransactionSynchronizationEventAdapter(ApplicationListenerMethodAdapter listener,
				ApplicationEvent event, TransactionPhase phase) {

			this.listener = listener;
			this.event = event;
			this.phase = phase;
		}

		@Override
		public int getOrder() {
			return this.listener.getOrder();
		}

		@Override
		public void beforeCommit(boolean readOnly) {
			if (this.phase == TransactionPhase.BEFORE_COMMIT) {
				processEvent();
			}
		}

		@Override
		public void afterCompletion(int status) {
			// commit 成功执行
			if (this.phase == TransactionPhase.AFTER_COMMIT && status == STATUS_COMMITTED) {
				processEvent();
			}
			// 失败执行
			else if (this.phase == TransactionPhase.AFTER_ROLLBACK && status == STATUS_ROLLED_BACK) {
				processEvent();
			}
			// 都执行
			else if (this.phase == TransactionPhase.AFTER_COMPLETION) {
				processEvent();
			}
		}

		protected void processEvent() {
			this.listener.processEvent(this.event);
		}
	}

}
