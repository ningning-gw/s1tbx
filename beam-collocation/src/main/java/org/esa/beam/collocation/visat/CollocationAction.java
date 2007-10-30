package org.esa.beam.collocation.visat;

import org.esa.beam.framework.ui.ModalDialog;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.visat.actions.AbstractVisatAction;

/**
 * Geographic collocation action.
 *
 * @author Ralf Quast
 * @version $Revision$ $Date$
 */
public class CollocationAction extends AbstractVisatAction {

    @Override
    public void actionPerformed(CommandEvent event) {
        final ModalDialog dialog = new CollocationDialog(getAppContext());
        dialog.show();
    }
}
