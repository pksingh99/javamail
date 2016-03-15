package org.jsoftware.javamail;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.URLName;
import javax.mail.event.TransportEvent;
import javax.mail.event.TransportListener;
import javax.mail.internet.InternetAddress;
import javax.naming.Context;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Properties;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SmtpJmsTransportTest {
    private BytesMessage bytesMessage;
    private QueueSender queueSender;
    private SmtpJmsTransport transport;
    private TransportListener transportListener;

    @Before
    public void setUp() throws Exception {
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, TestContextFactory.class.getName());
        QueueConnectionFactory queueConnectionFactory = Mockito.mock(QueueConnectionFactory.class);
        Queue queue = Mockito.mock(Queue.class);
        Context context = Mockito.mock(Context.class);
        TestContextFactory.context = context;
        when(context.lookup(eq("jms/queueConnectionFactory"))).thenReturn(queueConnectionFactory);
        when(context.lookup(eq("jms/mailQueue"))).thenReturn(queue);
        queueSender = Mockito.mock(QueueSender.class);
        QueueConnection queueConnection = Mockito.mock(QueueConnection.class);
        when(queueConnectionFactory.createQueueConnection()).thenReturn(queueConnection);
        when(queueConnectionFactory.createQueueConnection(anyString(), anyString())).thenReturn(queueConnection);
        QueueSession queueSession = Mockito.mock(QueueSession.class);
        bytesMessage = Mockito.mock(BytesMessage.class);
        when(queueSession.createBytesMessage()).thenReturn(bytesMessage);
        when(queueConnection.createQueueSession(anyBoolean(), anyInt())).thenReturn(queueSession);
        when(queueSession.createSender(eq(queue))).thenReturn(queueSender);
        transport = new SmtpJmsTransport(Session.getDefaultInstance(new Properties()), new URLName("jms"));
        transportListener = Mockito.mock(TransportListener.class);
        transport.addTransportListener(transportListener);
    }

    @Test(expected = MessagingException.class)
    public void testSendWithoutFrom() throws Exception {
        Message message = Mockito.mock(Message.class);
        transport.sendMessage(message, new Address[] { new InternetAddress("text@xtest.nowhere")});
    }

    @Test
    public void testSend() throws Exception {
        final InternetAddress toAddr =  new InternetAddress("text@xtest.nowhere");
        Message message = Mockito.mock(Message.class);
        when(message.getHeader(eq(SmtpJmsTransport.X_SEND_PRIORITY))).thenReturn(new String[]{"low"});
        when(message.getFrom()).thenReturn(new Address[] { new InternetAddress("from@server.com") });
        when(message.getSubject()).thenReturn("msgSubject!");
        transport.sendMessage(message, new Address[] { toAddr });
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(bytesMessage, times(1)).writeBytes(bytesCaptor.capture());
        byte[] messageBuffer = bytesCaptor.getValue();
        Assert.assertNotNull(messageBuffer);
        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(messageBuffer));
        String protocol = input.readUTF();
        Address[] inAddr = (Address[]) input.readObject();
        Assert.assertEquals(1, inAddr.length);
        Assert.assertEquals(toAddr, inAddr[0]);
        Assert.assertEquals("smtp", protocol);
        verify(message, times(1)).writeTo(any(ObjectOutputStream.class));
        verify(transportListener, times(1)).messageDelivered(any(TransportEvent.class));
    }

    @Test
    public void testSendNumberPriority() throws Exception {
        final int prio = 4;
        Message message = Mockito.mock(Message.class);
        when(message.getHeader(eq(SmtpJmsTransport.X_SEND_PRIORITY))).thenReturn(new String[]{Integer.toString(prio)});
        when(message.getFrom()).thenReturn(new Address[] { new InternetAddress("from@server.com") });
        transport.sendMessage(message, new Address[] { new InternetAddress("text@xtest.nowhere") });
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(bytesMessage, times(1)).setJMSPriority(captor.capture());
        Integer jmsPriority = captor.getValue();
        Assert.assertEquals(prio, jmsPriority.intValue());
    }

    @Test
    public void testSendInvalidPriority() throws Exception {
        SmtpJmsTransport transport = new SmtpJmsTransport(Session.getDefaultInstance(new Properties()), new URLName("jsm"));
        Message message = Mockito.mock(Message.class);
        when(message.getHeader(eq(SmtpJmsTransport.X_SEND_PRIORITY))).thenReturn(new String[]{"invalid"});
        when(message.getFrom()).thenReturn(new Address[] { new InternetAddress("from@server.com") });
        transport.sendMessage(message, new Address[] { new InternetAddress("text@xtest.nowhere") });
        verify(bytesMessage, never()).setJMSPriority(anyInt());
    }

    @Test
    public void testSendNumberPriorityXPriority() throws Exception {
        final int prio = 4;
        Message message = Mockito.mock(Message.class);
        when(message.getHeader(eq("X-Priority"))).thenReturn(new String[]{Integer.toString(prio)});
        when(message.getFrom()).thenReturn(new Address[] { new InternetAddress("from@server.com") });
        transport.sendMessage(message, new Address[] { new InternetAddress("text@xtest.nowhere") });
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(bytesMessage, times(1)).setJMSPriority(captor.capture());
        Integer jmsPriority = captor.getValue();
        Assert.assertEquals(prio, jmsPriority.intValue());
    }

    @Test
    public void testSendInvalidPriorityXPriority() throws Exception {
        Message message = Mockito.mock(Message.class);
        when(message.getHeader(eq("X-Priority"))).thenReturn(new String[]{"invalid"});
        when(message.getFrom()).thenReturn(new Address[] { new InternetAddress("from@server.com") });
        transport.sendMessage(message, new Address[] { new InternetAddress("text@xtest.nowhere") });
        verify(bytesMessage, never()).setJMSPriority(anyInt());
    }

    @Test
    public void testSendWithTTL() throws Exception {
        Message message = Mockito.mock(Message.class);
        when(message.getHeader(eq(SmtpJmsTransport.X_SEND_EXPIRE))).thenReturn(new String[]{"321"});
        when(message.getFrom()).thenReturn(new Address[] { new InternetAddress("from@server.com") });
        transport.sendMessage(message, new Address[] { new InternetAddress("text@xtest.nowhere") });
        ArgumentCaptor<Long> ttlLongArgumentCaptor = ArgumentCaptor.forClass(Long.class);
        verify(queueSender, times(1)).setTimeToLive(ttlLongArgumentCaptor.capture());
        Assert.assertEquals(Long.valueOf(321), ttlLongArgumentCaptor.getValue());
    }

    @Test
    public void testFailOnJms() throws Exception {
        Message message = Mockito.mock(Message.class);
        when(message.getFrom()).thenReturn(new Address[] { new InternetAddress("from@server.com") });
        doThrow(new JMSException("mock")).when(queueSender).send(any(javax.jms.Message.class));
        try {
            transport.sendMessage(message, new Address[]{new InternetAddress("text@xtest.nowhere")});
        } catch (MessagingException ex) {
            Thread.sleep(500);
            verify(transportListener, times(1)).messageNotDelivered(any(TransportEvent.class));
        }
    }
}