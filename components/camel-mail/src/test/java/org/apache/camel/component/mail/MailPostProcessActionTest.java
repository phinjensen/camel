/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.mail;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;

import org.apache.camel.BindToRegistry;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mail.Mailbox.MailboxUser;
import org.apache.camel.component.mail.Mailbox.Protocol;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit5.CamelTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests if post process action is called if it is set
 */
public class MailPostProcessActionTest extends CamelTestSupport {
    @SuppressWarnings({ "checkstyle:ConstantName" })
    private static final MailboxUser bill = Mailbox.getOrCreateUser("bill", "secret");
    private static final Logger LOG = LoggerFactory.getLogger(MailPostProcessActionTest.class);

    @BindToRegistry("postProcessAction")
    private TestPostProcessAction action = new TestPostProcessAction();

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        prepareMailbox();
        super.setUp();
    }

    @Test
    public void testActionCalled() throws Exception {
        Mailbox mailbox = bill.getInbox();
        assertEquals(1, mailbox.getMessageCount());

        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedBodiesReceivedInAnyOrder("TestText");

        MockEndpoint.assertIsSatisfied(context);
        waitForActionCalled();
    }

    private void waitForActionCalled() throws InterruptedException {
        // Wait for a maximum of 500 ms for the action to be called
        for (int i = 0; i < 50; i++) {
            if (action.hasBeenCalled()) {
                break;
            }
            LOG.debug("Sleeping for 10 millis to wait for action call");
            Thread.sleep(10);
        }
        assertEquals(true, action.hasBeenCalled());
    }

    private void prepareMailbox() throws Exception {
        // connect to mailbox
        Mailbox.clearAll();
        JavaMailSender sender = new DefaultJavaMailSender();
        Store store = sender.getSession().getStore("imap");
        store.connect("localhost", Mailbox.getPort(Protocol.imap), bill.getLogin(), bill.getPassword());
        Folder folder = store.getFolder("INBOX");
        folder.open(Folder.READ_WRITE);
        folder.expunge();

        // inserts 1 new message
        Message[] messages = new Message[1];
        messages[0] = new MimeMessage(sender.getSession());
        messages[0].setSubject("TestSubject");
        messages[0].setHeader("Message-ID", "0");
        messages[0].setText("TestText");

        folder.appendMessages(messages);
        folder.close(true);
    }

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            public void configure() {
                from(bill.uriPrefix(Protocol.imap) + "&postProcessAction=#postProcessAction&initialDelay=100&delay=100")
                        .to("mock:result");
            }
        };
    }

    private class TestPostProcessAction implements MailBoxPostProcessAction {
        private boolean called;

        @Override
        public void process(Folder folder) throws Exception {
            // Assert that we are looking at the correct folder with our message
            final Message[] messages = folder.getMessages();
            assertEquals(1, messages.length);
            assertEquals("TestSubject", messages[0].getSubject());
            // And mark ourselves as "called"
            called = true;
        }

        /**
         * @return true if the action has been called
         */
        public boolean hasBeenCalled() {
            return called;
        }
    }
}
