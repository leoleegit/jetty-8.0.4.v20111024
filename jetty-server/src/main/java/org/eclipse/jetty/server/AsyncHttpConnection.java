package org.eclipse.jetty.server;

import java.io.IOException;

import org.eclipse.jetty.http.FlashPolicyException;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class AsyncHttpConnection extends HttpConnection
{
    private final static int NO_PROGRESS_INFO = Integer.getInteger("org.mortbay.jetty.NO_PROGRESS_INFO",100);
    private final static int NO_PROGRESS_CLOSE = Integer.getInteger("org.mortbay.jetty.NO_PROGRESS_CLOSE",200);

    private static final Logger LOG = Log.getLogger(AsyncHttpConnection.class);
    private int _total_no_progress;

    public AsyncHttpConnection(Connector connector, EndPoint endpoint, Server server)
    {
        super(connector,endpoint,server);
    }

    public Connection handle() throws IOException
    {
        Connection connection = this;
        boolean some_progress=false;
        boolean progress=true;

        // Loop while more in buffer
        try
        {
            setCurrentConnection(this);

            boolean more_in_buffer =false;

            while (_endp.isOpen() && (more_in_buffer || progress) && connection==this)
            {
                progress=false;
                try
                {

                    // Handle resumed request
                    if (_request._async.isAsync() && !_request._async.isComplete())
                        handleRequest();

                    // else Parse more input
                    else if (!_parser.isComplete() && _parser.parseAvailable()>0)
                        progress=true;

                    // Generate more output
                    if (_generator.isCommitted() && !_generator.isComplete() && _generator.flushBuffer()>0)
                        progress=true;

                    // Flush output from buffering endpoint
                    if (_endp.isBufferingOutput())
                        _endp.flush();

                    // Special case close handling.
                    // If we were dispatched and have made no progress, but io is shutdown, then close
                    if (!progress && !some_progress && (_endp.isInputShutdown()||_endp.isOutputShutdown()))
                       _endp.close();
                }
                catch (FlashPolicyException e)
                {
                    _endp.flush(new ByteArrayBuffer("<cross-domain-policy><allow-access-from domain=\"*\" to-ports=\"*\" /></cross-domain-policy>\0"));
                    _parser.reset();
                    _endp.close();
                }
                catch (HttpException e)
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("uri="+_uri);
                        LOG.debug("fields="+_requestFields);
                        LOG.debug(e);
                    }
                    _generator.sendError(e.getStatus(), e.getReason(), null, true);
                    _parser.reset();
                    _endp.close();
                }
                finally
                {
                    // Do we need to complete a half close?
                    if (_endp.isInputShutdown() && (_parser.isIdle() || _parser.isComplete()))
                    {
                        LOG.debug("complete half close {}",this);
                        more_in_buffer=false;
                        _endp.close();
                        reset(true);
                    }

                    // else Is this request/response round complete?
                    else if (_parser.isComplete() && _generator.isComplete() && !_endp.isBufferingOutput())
                    {
                        // look for a switched connection instance?
                        if (_response.getStatus()==HttpStatus.SWITCHING_PROTOCOLS_101)
                        {
                            Connection switched=(Connection)_request.getAttribute("org.eclipse.jetty.io.Connection");
                            if (switched!=null)
                            {
                                _parser.reset();
                                _generator.reset(true);
                                connection=switched;
                            }
                        }

                        // Reset the parser/generator
                        // keep the buffers as we will cycle
                        progress=true;
                        reset(false);
                        more_in_buffer = _parser.isMoreInBuffer() || _endp.isBufferingInput();
                    }
                    // else Are we suspended?
                    else if (_request.isAsyncStarted())
                    {
                        LOG.debug("suspended {}",this);
                        more_in_buffer=false;
                        progress=false;
                    }
                    else
                        more_in_buffer = _parser.isMoreInBuffer() || _endp.isBufferingInput();

                    some_progress|=progress|((SelectChannelEndPoint)_endp).isProgressing();
                }
            }
        }
        finally
        {
            setCurrentConnection(null);
            _parser.returnBuffers();
            _generator.returnBuffers();

            // Check if we are write blocked
            if (_generator.isCommitted() && !_generator.isComplete() && _endp.isOpen() && !_endp.isOutputShutdown())
                ((AsyncEndPoint)_endp).scheduleWrite(); // TODO. This should not be required

            if (some_progress)
            {
                _total_no_progress=0;
            }
            else
            {
                int totalNoProgress=++_total_no_progress;

                if (NO_PROGRESS_INFO>0 && totalNoProgress==NO_PROGRESS_INFO && (NO_PROGRESS_CLOSE<=0 || totalNoProgress<NO_PROGRESS_CLOSE))
                {
                    LOG.info("EndPoint making no progress: {} {}", totalNoProgress, _endp);
                }

                if (NO_PROGRESS_CLOSE>0 && totalNoProgress==NO_PROGRESS_CLOSE)
                {
                    LOG.warn("Closing EndPoint making no progress: {} {}", totalNoProgress, _endp);
                    if (_endp instanceof SelectChannelEndPoint)
                    {
                        System.err.println(((SelectChannelEndPoint)_endp).getSelectManager().dump());
                        ((SelectChannelEndPoint)_endp).getChannel().close();
                    }
                }
            }
        }
        return connection;
    }

}
