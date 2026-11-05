/*
 * Link-time stand-ins for the base image's GStreamer and GLib (Droidtop/tracker#601). libgstdroidtopff.so is loaded
 * by the image's own GStreamer inside Wine's process, so it must name the image's libraries as NEEDED, but droidtop
 * builds against headers only. CMake compiles this file three times, as libgstreamer-1.0.so.0, libgobject-2.0.so.0
 * and libglib-2.0.so.0, each with the symbols of its library (-DSTUB_GST, -DSTUB_GOBJECT, -DSTUB_GLIB). The plugin
 * links against them with -Wl,--no-undefined, so a symbol missing here fails the build; at run time the dynamic
 * linker binds every one of them to the image's real library. These stubs are never packaged.
 */
#define F(name) void name(void) {}
#if defined(STUB_GST)
unsigned long _gst_fraction_type;
F(gst_buffer_fill) F(gst_buffer_new_allocate) F(gst_caps_new_simple) F(gst_element_add_pad)
F(gst_element_class_add_static_pad_template) F(gst_element_class_set_static_metadata) F(gst_element_get_type)
F(gst_element_no_more_pads) F(gst_element_post_message) F(gst_element_register) F(gst_element_remove_pad)
F(gst_event_new_caps) F(gst_event_new_eos) F(gst_event_new_segment) F(gst_event_new_stream_start)
F(gst_message_new_error) F(gst_mini_object_unref) F(gst_pad_create_stream_id) F(gst_pad_new_from_static_template)
F(gst_pad_pause_task) F(gst_pad_push) F(gst_pad_push_event) F(gst_pad_set_active) F(gst_pad_set_chain_function_full)
F(gst_pad_set_event_function_full) F(gst_pad_start_task) F(gst_pad_stop_task) F(gst_pad_use_fixed_caps)
F(gst_plugin_register_static) F(gst_segment_init) F(gst_stream_error_quark)
#elif defined(STUB_GOBJECT)
F(g_type_class_adjust_private_offset) F(g_type_class_peek_parent) F(g_type_register_static_simple)
#elif defined(STUB_GLIB)
F(g_error_free) F(g_error_new_literal) F(g_free) F(g_intern_static_string) F(g_once_init_enter) F(g_once_init_leave)
#endif
