package com.jcope.vnc.server.input.handle;

import static com.jcope.debug.Debug.assert_;

import com.jcope.vnc.server.ClientHandler;
import com.jcope.vnc.shared.input.Handle;

public class EnableConnectionMonitor extends Handle<ClientHandler>
{
    public EnableConnectionMonitor()
    {
        super(ClientHandler.class);
    }
    
    @Override
    public void handle(ClientHandler client, Object[] args)
    {
        assert_(true); // TODO: remove me and finish
    }
}
