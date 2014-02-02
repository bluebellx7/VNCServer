package com.jcope.vnc.server;

import static com.jcope.debug.Debug.DEBUG;
import static com.jcope.debug.Debug.assert_;
import static com.jcope.vnc.shared.ScreenSelector.getScreenDevices;

import java.awt.AWTException;
import java.awt.DisplayMode;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.peer.MouseInfoPeer;
import java.awt.peer.RobotPeer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import sun.awt.ComponentFactory;

import com.jcope.debug.LLog;
import com.jcope.ui.DirectBufferedImage;

public final class DirectRobot
{
    public final GraphicsDevice device;
    public final Robot robot;
    
    private Object getRGBPixelsMethodParam;
    private int getRGBPixelsMethodType;
    private Method getRGBPixelsMethod;
    private final RobotPeer peer;
    private static boolean hasMouseInfoPeer;
    private static MouseInfoPeer mouseInfoPeer;
    private WeakHashMap<int[],BufferedImage> nonAlphaCache = new WeakHashMap<int[],BufferedImage>();
    private WeakHashMap<int[],BufferedImage> alphaCache = new WeakHashMap<int[],BufferedImage>();
    private boolean isDirty = true, usedEfficientMethod;
    private int[] pixelCache;
    private int width, height, numPixels;
    
    private Semaphore getPixelsSema = new Semaphore(1, true);
    private ReentrantLock methLock = new ReentrantLock(true);
    private ReentrantLock cacheSyncLock = new ReentrantLock(true);
    
    public DirectRobot() throws AWTException
	{
		this(null);
	}

	public DirectRobot(GraphicsDevice device) throws AWTException
	{
		if (device == null)
		{
			device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
		}
		robot = new Robot(device);
		
		ArrayList<Exception> exceptions = new ArrayList<Exception>();

		this.device = device;
		Toolkit toolkit = Toolkit.getDefaultToolkit();
		peer = ((ComponentFactory) toolkit).createRobot(null, device);
		Class<?> peerClass = peer.getClass();
		Method method = null;
		int methodType = -1;
		Object methodParam = null;
		try
		{
			method = peerClass.getDeclaredMethod("getRGBPixels", new Class<?>[] { Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, int[].class });
			methodType = 0;
		}
		catch (SecurityException e)
		{
			exceptions.add(e);
		}
		catch (NoSuchMethodException e)
		{
			exceptions.add(e);
		}
		if (methodType < 0)
		{
			try
			{
				method = peerClass.getDeclaredMethod("getScreenPixels", new Class<?>[] { Rectangle.class, int[].class });
				methodType = 1;
			}
			catch (SecurityException e)
			{
				exceptions.add(e);
			}
			catch (NoSuchMethodException e)
			{
				exceptions.add(e);
			}
		}

		if (methodType < 0)
		{
			try
			{
				method = peerClass.getDeclaredMethod("getScreenPixels", new Class<?>[] { Integer.TYPE, Rectangle.class, int[].class });
				methodType = 2;
				GraphicsDevice[] devices = getScreenDevices(); 
				int count = devices.length;
				for (int i = 0; i != count; ++i)
				{
					if (device.equals(devices[i]))
					{
						methodParam = Integer.valueOf(i);
						break;
					}
				}

			}
			catch (SecurityException e)
			{
				exceptions.add(e);
			}
			catch (NoSuchMethodException e)
			{
				exceptions.add(e);
			}
		}

		if (methodType < 0)
		{
			try
			{
				boolean accessibleChanged = false;
				
				method = peerClass.getDeclaredMethod("getRGBPixelsImpl", new Class<?>[] { Class.forName("sun.awt.X11GraphicsConfig"), Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, int[].class });
				Field field = peerClass.getDeclaredField("xgc");
				try
				{
				    if (!field.isAccessible())
				    {
    					field.setAccessible(true);
    					accessibleChanged = true;
				    }
					methodParam = field.get(peer);
					methodType = 3;
				}
				catch (IllegalArgumentException e)
				{
				    exceptions.add(e);
				}
				catch (IllegalAccessException e)
				{
				    exceptions.add(e);
				}
				finally {
					if (accessibleChanged)
					{
						field.setAccessible(false);
					}
				}
			}
			catch (SecurityException e)
			{
			    exceptions.add(e);
			}
			catch (NoSuchFieldException e)
			{
			    exceptions.add(e);
			}
			catch (NoSuchMethodException e)
			{
			    exceptions.add(e);
			}
			catch (ClassNotFoundException e)
			{
			    exceptions.add(e);
			}
		}

		if (methodType >= 0 && method != null && (methodType <= 1 || methodParam != null))
		{
			if (DEBUG) LLog.i(String.format("Method type = %d", methodType));
			getRGBPixelsMethod = method;
			getRGBPixelsMethodType = methodType;
			getRGBPixelsMethodParam = methodParam;
		}
		else
		{
		    if (DEBUG)
		    {
    			for (Exception e : exceptions)
    			{
    				e.printStackTrace();
    			}
		    }
			exceptions.clear();
			exceptions = null;
			if (DEBUG)
			{
				LLog.w(String.format("WARNING: Failed to acquire direct method for grabbing pixels, please post this on the main thread!\n\n%s\n\n", peer.getClass().getName()));
				try
				{
					Method[] methods = peer.getClass().getDeclaredMethods();
					for (Method method1 : methods)
					{
						LLog.w(method1 + "\n");
					}
		
					LLog.w("\n");
				}
				catch (Exception e)
				{
					LLog.e(e);
				}
			}
			throw new RuntimeException("No supported method for getting pixels");
		}
	}

	public static GraphicsDevice getMouseInfo(Point point)
	{
	    GraphicsDevice rval = null;
	    
	    if (!hasMouseInfoPeer)
		{
			hasMouseInfoPeer = true;
			mouseInfoPeer = null;
			ArrayList<Exception> exceptions = new ArrayList<Exception>();
			try
			{
				Toolkit toolkit = Toolkit.getDefaultToolkit();
				Method method = toolkit.getClass().getDeclaredMethod("getMouseInfoPeer", new Class<?>[0]);
				boolean accessibleChanged = false;
				try
				{
				    if (!method.isAccessible())
				    {
				        method.setAccessible(true);
				        accessibleChanged = true;
				    }
					mouseInfoPeer = (MouseInfoPeer) method.invoke(toolkit, new Object[0]);
				}
				catch (IllegalArgumentException e)
				{
					exceptions.add(e);
				}
				catch (IllegalAccessException e)
				{
				    exceptions.add(e);
				}
				catch (InvocationTargetException e)
				{
				    exceptions.add(e);
				}
				finally {
				    if (accessibleChanged)
				    {
				        method.setAccessible(false);
				    }
				}
			}
			catch (SecurityException e)
			{
			    exceptions.add(e);
			}
			catch (NoSuchMethodException e)
			{
			    exceptions.add(e);
			}
			
	        if (DEBUG)
            {
                for (Exception e : exceptions)
                {
                    e.printStackTrace();
                }
            }
            exceptions.clear();
            exceptions = null;
            
		}
		if (mouseInfoPeer == null)
		{
		    PointerInfo info = MouseInfo.getPointerInfo();
		    
            if (point != null)
            {
                Point tmpPoint = info.getLocation();
                point.x = tmpPoint.x;
                point.y = tmpPoint.y;
            }
            
            rval = info.getDevice();
		}
		else
		{
		    int device = mouseInfoPeer.fillPointWithCoords(point != null ? point : new Point());
            GraphicsDevice[] devices = getScreenDevices();
            
            rval = devices[device];
		}
		
		if (rval != null)
		{
		    // found an instance where garbage ridiculously large values were
		    // returned when coming back from lock screen on windows7
		    int[] initialScale = new int[2];
		    Rectangle bounds = _getScreenBounds(rval, initialScale);
		    
		    point.x -= bounds.x;
            point.y -= bounds.y;
            
            if (initialScale[0] != bounds.width || initialScale[1] != bounds.height)
            {
                point.x = Math.round(((float)bounds.width)/((float)initialScale[0])*((float)point.x));
                point.y = Math.round(((float)bounds.height)/((float)initialScale[1])*((float)point.y));
            }
            
            if (point.x < 0)
            {
                point.x = 0;
            }
            else if (point.x > bounds.width)
            {
                point.x = bounds.width;
            }
            
            if (point.y < 0)
            {
                point.y = 0;
            }
            else if (point.y > bounds.height)
            {
                point.y = bounds.height;
            }
		}
		
		return rval;
	}

	public static int getNumberOfMouseButtons()
	{
		return MouseInfo.getNumberOfButtons();
	}

	public static GraphicsDevice getDefaultScreenDevice()
	{
		return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
	}
	
	public static GraphicsDevice getScreenDevice()
	{
		return getMouseInfo(null);
	}
	
	public Rectangle getScreenBounds()
	{
	    return getScreenBounds(device);
	}
	
	public static Rectangle getScreenBounds(GraphicsDevice device)
	{
	    return _getScreenBounds(device, null);
	}
	
	private static Rectangle _getScreenBounds(GraphicsDevice device, int[] initialScale)
    {
	    GraphicsConfiguration defaultConf = device.getDefaultConfiguration();
	    Rectangle rval = defaultConf.getBounds();
	    DisplayMode dispMode = device.getDisplayMode();
	    int dispModeW = dispMode.getWidth(), dispModeH = dispMode.getHeight();
	    
        // x and y describe relative origin
        // width and height are true width and height of the graphics device
        
	    for (GraphicsConfiguration gc : device.getConfigurations())
	    {
	        if (gc.equals(defaultConf))
	        {
	            continue;
	        }
	        
	        Rectangle.union(gc.getBounds(), rval, rval);
	    }
	    
	    // workaround for bug where for some reason
	    // the bounds do not match the displaymode bounds
	    
	    if (initialScale != null)
	    {
            initialScale[0] = rval.width;
            initialScale[1] = rval.height;
	    }
        
        assert_(dispModeW >= rval.width);
        assert_(dispModeH >= rval.height);
	    
        rval.width = dispModeW;
	    rval.height = dispModeH;
        
        return rval;
    }
	
	public void mouseMove(int x, int y)
	{
	    mouseMove(x, y, 0, 0);
	}

    public void mouseMove(int x, int y, int ox, int oy)
	{
        // Appears that peer does not require inverse scaling
        // when attempting to move the mouse
        /*
        int[] initialScale = new int[2];
        Rectangle bounds = _getScreenBounds(device, initialScale);
        
        if (initialScale[0] != bounds.width || initialScale[1] != bounds.height)
        {
            x = Math.round(((float)initialScale[0])/((float)bounds.width)*((float)x));
            y = Math.round(((float)initialScale[1])/((float)bounds.height)*((float)y));
        }
        */
        
        x += ox;
        y += oy;
        
		peer.mouseMove(x, y);
	}

	public void mousePress(int buttonID)
	{
		peer.mousePress(buttonID);
	}

	public void mouseRelease(int buttonID)
	{
		peer.mouseRelease(buttonID);
	}

	public void mouseWheel(int wheelAmount)
	{
		peer.mouseWheel(wheelAmount);
	}

	public void keyPress(int keycode)
	{
		peer.keyPress(keycode);
	}

	public void keyRelease(int keycode)
	{
		peer.keyRelease(keycode);
	}

	public int getRGBPixel(int x, int y)
	{
		return peer.getRGBPixel(x, y);
	}

	public int[] getRGBPixels(Rectangle bounds)
	{
		return peer.getRGBPixels(bounds);
	}

	public static void getRGBPixelSlice(int[] src, int srcWidth, int srcHeight, int x, int y, int width, int height, int[] dst)
	{
		assert_(width > 0);
		assert_(height > 0);
		assert_(srcWidth > width);
		assert_(srcHeight > height);
		
		int srcPos = y * srcWidth + x;
		int dstPos = 0;
		
		for (int i=0; i<height; i++)
		{
			System.arraycopy(src, srcPos, dst, dstPos, width);
			srcPos += srcWidth;
			dstPos += width;
		}
	}
	
	public void markRGBCacheDirty()
	{
		isDirty = true;
	}
	
	public boolean getRGBPixels(int x, int y, int width, int height, int[] pixels)
	{
	    try
	    {
	        getPixelsSema.acquire();
	    }
        catch (InterruptedException e)
        {
            LLog.e(e);
        }
	    try
	    {
    		if (isDirty)
    		{
    			isDirty = false;
    			usedEfficientMethod = _getRGBPixels();
    		}
    		else
    		{
    			usedEfficientMethod = true;
    		}
    		
    		getRGBPixelSlice(pixelCache, this.width, this.height, x, y, width, height, pixels);
    		
    		return usedEfficientMethod;
	    }
	    finally {
	        getPixelsSema.release();
	    }
	}
	
	private boolean _getRGBPixels()
	{
		Rectangle r = getScreenBounds();
		numPixels = r.width * r.height;
		width = r.width;
		height = r.height;
		if (pixelCache == null || pixelCache.length != numPixels)
		{
			pixelCache = new int[numPixels];
		}
		if (getRGBPixelsMethod != null)
		{
			try
			{
				methLock.lock();
				boolean makeAccessible = !getRGBPixelsMethod.isAccessible();
				synchronized(getRGBPixelsMethod)
				{
					if (makeAccessible)
					{
						getRGBPixelsMethod.setAccessible(true);
					}
					try
					{
						switch(getRGBPixelsMethodType)
						{
							case 0:
								getRGBPixelsMethod.invoke(peer, new Object[] { Integer.valueOf(r.x), Integer.valueOf(r.y), Integer.valueOf(width), Integer.valueOf(height), pixelCache });
								break;
							case 1:
								getRGBPixelsMethod.invoke(peer, new Object[] { new Rectangle(r.x, r.y, width, height), pixelCache });
								break;
							case 2:
								getRGBPixelsMethod.invoke(peer, new Object[] { getRGBPixelsMethodParam, new Rectangle(r.x, r.y, width, height), pixelCache });
								break;
							default:
								getRGBPixelsMethod.invoke(peer, new Object[] { getRGBPixelsMethodParam, Integer.valueOf(r.x), Integer.valueOf(r.y), Integer.valueOf(width), Integer.valueOf(height), pixelCache });
								break;
						}
					}
					finally {
						if (makeAccessible)
						{
							getRGBPixelsMethod.setAccessible(false);
						}
					}
				}

				return true;
			} catch (IllegalArgumentException e) {
				LLog.e(e);
			} catch (IllegalAccessException e) {
				LLog.e(e);
			} catch (InvocationTargetException e) {
				LLog.e(e);
			}
			finally {
				methLock.unlock();
			}
		}
		
		int[] tmp = getRGBPixels(r);
		System.arraycopy(tmp, 0, pixelCache, 0, numPixels);
		return false;
	}

	public void dispose()
	{
		getRGBPixelsMethodParam = null;
		Method method = getRGBPixelsMethod;
		try
		{
			if (method != null)
			{
				getRGBPixelsMethod = null;
				method.setAccessible(false);
			}
		}
		finally {
			try
			{
				Class<?>[] tailSig = new Class<?>[0];
				peer.getClass().getDeclaredMethod("dispose", tailSig).invoke(peer, (Object[])tailSig);
			}
			catch (IllegalArgumentException e)
			{
				LLog.e(e);
			}
			catch (SecurityException e)
			{
				LLog.e(e);
			}
			catch (IllegalAccessException e)
			{
				LLog.e(e);
			}
			catch (InvocationTargetException e)
			{
				LLog.e(e);
			}
			catch (NoSuchMethodException e)
			{
				LLog.e(e);
			}
		}
	}

	protected void finalize() throws Throwable
	{
		try
		{
			dispose();
		}
		finally {
			super.finalize();
		}
	}
	
	public void clearBufferedImage(int[] pixels)
	{
		cacheSyncLock.lock();
		try
		{
			synchronized(alphaCache){synchronized(nonAlphaCache){
				alphaCache.remove(pixels);
				nonAlphaCache.remove(pixels);
			}}
		}
		finally {
			cacheSyncLock.unlock();
		}
	}
	
	public BufferedImage getBufferedImage(int[] pixels, int size, int width, int height, boolean hasAlpha)
	{
		BufferedImage image = null;
		
		assert_(size > 0);
		assert_(width > 0);
		assert_(height > 0);
		assert_(size == (width * height));
		assert_(pixels.length >= size);
		
		cacheSyncLock.lock();
		try 
		{
			synchronized(alphaCache){synchronized(nonAlphaCache){
				image = (hasAlpha ? alphaCache : nonAlphaCache).get(pixels);
				
				if (image == null)
				{
					image = new DirectBufferedImage(pixels, size, width, height, hasAlpha);
					
					(hasAlpha ? alphaCache : nonAlphaCache).put(pixels, image);
				}
			}}
		}
		finally {
			cacheSyncLock.unlock();
		}
		
		return image;
	}
	
	public int[] getRGBPixels()
    {
	    try
        {
            getPixelsSema.acquire();
        }
        catch (InterruptedException e)
        {
            LLog.e(e);
        }
        try
        {
            _getRGBPixels();
            return pixelCache;
        }
        finally {
            getPixelsSema.release();
        }
    }
}